/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged. This file lived only
 * in the local commit that died with the box and is NOT touched by the 18c
 * patch, so it had to be rebuilt from two independent sources:
 *
 *   - A whole-file transcript snapshot (a `cat` of this path taken 2026-08-15,
 *     preserved in pkrecover/fragments/IPrivacyKitManager.aidl.txt under the
 *     marker "===HISTORYSTORE===") supplies the authored text verbatim:
 *     javadoc, constant names, member order, load()/save()/getRecent()/format()
 *     /trimToCap() bodies.
 *   - The r49 services.jar decompile (jadx, built 2026-08-18 13:44 UTC) supplies
 *     the two changes made to this file AFTER that snapshot was taken:
 *       1. record() gained the consecutive-duplicate collapse branch. Confirmed
 *          both by the r49 dex and by the comment in PrivacyKitService that
 *          states record() "only collapses *identical* consecutive entries".
 *       2. exportToJson() was added for the backup/restore feature; its sole
 *          caller is PrivacyKitService.exportBackupInternal(), which does
 *          `root.put("history", new JSONArray(mHistoryStore.exportToJson()))`,
 *          pinning the return to a JSON *array* string.
 *
 * Everything else is byte-for-byte the snapshot. Behaviour, on-disk format
 * (history.xml: tag/attribute names, write-detail-only-when-non-null), the
 * 200-entry cap, the "yyyy-MM-dd HH:mm"/Locale.US display format and the exact
 * Slog messages were all re-verified against the r49 dex.
 *
 * Known cosmetic ambiguity (functionally irrelevant, called out rather than
 * guessed silently): R8 inlines every `static final String`, so the r49 dex
 * cannot say whether exportToJson()'s four JSON keys were written as the
 * ATTR_* constants or as separate JSON_* constants the way
 * PrivacyKitProfileStore declares them. The XML attribute names and the JSON
 * keys are the same four strings in the shipped build either way; the ATTR_*
 * constants are reused below so no name is invented.
 */
package com.android.server.privacykit;

import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Persists a capped rolling activity log (rule changes, profile management)
 * to /data/system/privacykit/history.xml, for display on the Settings
 * History tab. Kept fully separate from PrivacyKitProfileStore - its own
 * file, its own class, no shared state - so this purely-additive,
 * lower-stakes feature can never risk the already-verified-working profile
 * store.
 *
 * The log is capped at MAX_ENTRIES entries; once full, recording a new
 * entry drops the single oldest one. Entries are stored oldest-first
 * internally; getRecent() returns them newest-first for display.
 *
 * Unlike PrivacyKitProfileStore, this class synchronizes its own methods
 * internally, so callers do not need to hold any external lock.
 */
public class PrivacyKitHistoryStore {
    private static final String TAG = "PrivacyKitHistoryStore";

    private static final int MAX_ENTRIES = 200;

    private static final String TAG_HISTORY = "history";
    private static final String TAG_ENTRY = "entry";
    private static final String ATTR_TS = "ts";
    private static final String ATTR_PKG = "pkg";
    private static final String ATTR_ACTION = "action";
    private static final String ATTR_DETAIL = "detail";

    private static final class Entry {
        final long timestampMillis;
        final String packageName;
        final String action;
        final String detail;

        Entry(long timestampMillis, String packageName, String action, String detail) {
            this.timestampMillis = timestampMillis;
            this.packageName = packageName;
            this.action = action;
            this.detail = detail;
        }
    }

    // Oldest-first; newest entries are appended at the end.
    private final List<Entry> mEntries = new ArrayList<>();

    private final AtomicFile mHistoryFile;

    private final SimpleDateFormat mDateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    public PrivacyKitHistoryStore(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        mHistoryFile = new AtomicFile(new File(dir, "history.xml"));
    }

    public synchronized void load() {
        if (!mHistoryFile.getBaseFile().exists()) {
            return;
        }
        try (FileInputStream fis = mHistoryFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                if (TAG_ENTRY.equals(parser.getName())) {
                    long ts = parser.getAttributeLong(null, ATTR_TS, 0L);
                    String pkg = parser.getAttributeValue(null, ATTR_PKG);
                    String action = parser.getAttributeValue(null, ATTR_ACTION);
                    String detail = parser.getAttributeValue(null, ATTR_DETAIL);
                    if (pkg != null && action != null) {
                        mEntries.add(new Entry(ts, pkg, action, detail));
                    }
                }
            }
            trimToCap();
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to load history.xml, starting empty", e);
        }
    }

    /**
     * Appends a new entry, trims to MAX_ENTRIES (dropping the oldest one if
     * over cap), and persists.
     *
     * <p>A repeat of the entry currently at the end - same package, same
     * action and an equal detail string - replaces that entry with a
     * fresh timestamp instead of appending a second copy. Only *consecutive*
     * identical entries collapse, and only into one: this exists so a control
     * toggled back and forth, or a rule re-applied by an enforcement pass,
     * cannot evict genuinely different history out of a 200-entry log, not as
     * general de-duplication. Anything with a different detail string - which
     * includes every keystroke of an edited note - is a distinct entry.
     */
    public synchronized void record(String packageName, String action, String detail) {
        if (!mEntries.isEmpty()) {
            Entry last = mEntries.get(mEntries.size() - 1);
            if (last.packageName.equals(packageName)
                    && last.action.equals(action)
                    && Objects.equals(last.detail, detail)) {
                mEntries.set(mEntries.size() - 1,
                        new Entry(System.currentTimeMillis(), packageName, action, detail));
                // Replacing in place cannot grow the list, so no trimToCap().
                save();
                return;
            }
        }
        mEntries.add(new Entry(System.currentTimeMillis(), packageName, action, detail));
        trimToCap();
        save();
    }

    /**
     * Up to {@code max} most-recent entries, newest-first, each pre-formatted
     * as a single human-readable display string (e.g. "2026-08-15 05:44
     * com.example.app  RULE_SET  android_id -> 1"). Formatting is done here
     * so the AIDL surface stays a plain List<String>, no custom Parcelable.
     */
    public synchronized List<String> getRecent(int max) {
        List<String> result = new ArrayList<>();
        for (int i = mEntries.size() - 1; i >= 0 && result.size() < max; i--) {
            result.add(format(mEntries.get(i)));
        }
        return result;
    }

    /**
     * The whole log as a JSON array string, for the "Export backup" action.
     * Structured rather than pre-formatted (unlike {@link #getRecent(int)}),
     * so a backup keeps the raw timestamp and fields; the omitted "detail" key
     * means the entry had none, exactly as in history.xml.
     *
     * <p>Performs no I/O: the caller (PrivacyKitService.exportBackupInternal)
     * embeds the result in the larger backup document.
     */
    public synchronized String exportToJson() {
        JSONArray entries = new JSONArray();
        try {
            for (Entry e : mEntries) {
                JSONObject obj = new JSONObject();
                obj.put(ATTR_TS, e.timestampMillis);
                obj.put(ATTR_PKG, e.packageName);
                obj.put(ATTR_ACTION, e.action);
                if (e.detail != null) {
                    obj.put(ATTR_DETAIL, e.detail);
                }
                entries.put(obj);
            }
        } catch (JSONException e) {
            // Every value put() here is a plain String/long already held in
            // memory - this should be unreachable - but fail safe with the
            // entries built so far rather than crashing the export.
            Slog.e(TAG, "Failed to build history export JSON", e);
        }
        return entries.toString();
    }

    private String format(Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(mDateFormat.format(new Date(e.timestampMillis)));
        sb.append("  ").append(e.packageName);
        sb.append("  ").append(e.action);
        if (e.detail != null && !e.detail.isEmpty()) {
            sb.append("  ").append(e.detail);
        }
        return sb.toString();
    }

    private void trimToCap() {
        int excess = mEntries.size() - MAX_ENTRIES;
        if (excess > 0) {
            mEntries.subList(0, excess).clear();
        }
    }

    private void save() {
        FileOutputStream fos = null;
        try {
            fos = mHistoryFile.startWrite();
            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_HISTORY);
            for (Entry e : mEntries) {
                out.startTag(null, TAG_ENTRY);
                out.attributeLong(null, ATTR_TS, e.timestampMillis);
                out.attribute(null, ATTR_PKG, e.packageName);
                out.attribute(null, ATTR_ACTION, e.action);
                if (e.detail != null) {
                    out.attribute(null, ATTR_DETAIL, e.detail);
                }
                out.endTag(null, TAG_ENTRY);
            }
            out.endTag(null, TAG_HISTORY);
            out.endDocument();
            mHistoryFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save history.xml", e);
            if (fos != null) {
                mHistoryFile.failWrite(fos);
            }
        }
    }
}
