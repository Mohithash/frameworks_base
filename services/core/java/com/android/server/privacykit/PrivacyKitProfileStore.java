/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
// Reconstructed 2026-08-19 after the build box was purged; this is the final state as
// shipped in the r49 ROM (built 2026-08-18 13:44 UTC). Recovery route:
// snap18c/patches/frameworks_base.patch carries this file's complete base->final diff
// (24 hunks); the 24 regions the patch does not touch were spliced in from the
// 2026-08-16T09:31 whole-file transcript snapshot, each located by a unique 3-line
// anchor match on both sides and each exactly the length the hunk headers require,
// giving a strictly monotonic 1:1 mapping and a 1220-line result - the exact length the
// patch implies. The on-disk format (profiles.xml / generated.xml: tag and attribute
// names, the write-only-when-set attributes, the "pkg|profile|key" genKey, note
// truncation at 256) was verified attribute-by-attribute against the r49 services.jar
// decompile (jadx), as were loadProfiles, saveProfiles, flushGenerated,
// refreshHotPathFlags, isFreezableValue, isValidColor/isValidMode/sanitizeNote and
// setRule.
package com.android.server.privacykit;

import android.privacykit.PrivacyKitIdentifierGenerator;
import android.privacykit.PrivacyKitKeys;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.IoThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists PrivacyKit-Native per-app identity rules to
 * /data/system/privacykit/profiles.xml, plus a separate generated-values
 * cache so RULE_STATIC values stay stable across app launches/reboots.
 *
 * Each package can have multiple named Profiles (e.g. "Default", "Work"),
 * exactly one of which is "active" at a time. All the original single-
 * profile-per-app methods (hasProfile/getRule/setRule/clearRule/
 * clearProfile/getGeneratedValue/putGeneratedValue) transparently operate
 * on a package's *active* profile, so the framework hook sites that already
 * call these (SettingsProvider, PhoneSubInfoController, ActivityThread's
 * identity injector) needed zero changes for this rework - only the new
 * profile-management methods below are new API surface.
 *
 * Not thread-safe on its own; callers (PrivacyKitService) are responsible
 * for synchronizing access.
 */
public class PrivacyKitProfileStore {
    private static final String TAG = "PrivacyKitProfileStore";

    public static final String MODE_ISOLATED = "isolated";
    public static final String MODE_HYBRID = "hybrid";
    public static final String MODE_SHARED = "shared";

    /**
     * Profile colour tokens.
     *
     * <p>Stored as one of these stable names rather than as a packed ARGB int
     * on purpose. The same profile has to be drawn legibly on a light
     * background, on a dark background, and against whatever Material You
     * dynamic-colour scheme the device happens to be running that day; a
     * persisted int can only ever be correct for one of those, and would go
     * wrong the moment the user switched theme. A token lets the store remember
     * only *which* of seven slots the user picked and leaves resolving it to a
     * real colour to the UI, which is the only layer that knows the theme.
     *
     * <p>Being a closed, human-readable set has two further benefits: a
     * hand-edited backup can be validated ({@link #isValidColor}) instead of
     * blindly trusted, and a backup restores to the colour the user actually
     * chose even on a device whose dynamic palette differs from the one it was
     * exported from.
     *
     * <p>{@code null} - the default, and what every profile written before
     * colours existed loads as - means "no colour chosen". That is deliberately
     * not a token: "unset" and "deliberately grey" are different user intents
     * and the UI may want to draw them differently (no dot vs a grey dot).
     */
    public static final String COLOR_BLUE = "blue";
    public static final String COLOR_GREEN = "green";
    public static final String COLOR_AMBER = "amber";
    public static final String COLOR_RED = "red";
    public static final String COLOR_PURPLE = "purple";
    public static final String COLOR_TEAL = "teal";
    public static final String COLOR_GREY = "grey";

    /**
     * Every valid colour token; what {@link #isValidColor} checks against.
     * Private on purpose - the Settings UI runs out of process and cannot read
     * it, so it hardcodes the same seven tokens itself, exactly as it already
     * hardcodes the MODE_* strings. The authoritative list is this one: an
     * unknown token from anywhere is dropped rather than stored.
     */
    private static final String[] COLOR_TOKENS = {
            COLOR_BLUE, COLOR_GREEN, COLOR_AMBER, COLOR_RED, COLOR_PURPLE, COLOR_TEAL, COLOR_GREY,
    };

    /**
     * Longest per-profile note kept, in characters.
     *
     * <p>A note is a UI affordance ("this is my throwaway shopping profile"),
     * not a data store. Every profile of every managed package is held in
     * memory and rewritten to profiles.xml in full on every single save, so an
     * unbounded free-text field would let one paste bloat the file and slow
     * every subsequent rule change. Anything longer is truncated rather than
     * rejected - see {@link #sanitizeNote}.
     */
    public static final int MAX_NOTE_LENGTH = 256;

    private static final String TAG_PROFILES = "profiles";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PROFILE = "profile";
    private static final String TAG_RULE = "rule";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_ID = "id";
    private static final String ATTR_MODE = "mode";
    private static final String ATTR_ACTIVE = "active";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_COLOR = "color";
    private static final String ATTR_NOTE = "note";
    private static final String ATTR_LAST_USED = "lastUsed";
    // Root-element attribute: the developer ADB-control gate. Written only
    // when on, so a store that never enabled it serialises the same bytes.
    private static final String ATTR_ADB_CONTROL = "adbControl";

    private static final String TAG_GENERATED = "generated";
    private static final String TAG_ENTRY = "entry";
    private static final String ATTR_PKG = "pkg";
    private static final String ATTR_PROFILE_ID = "profile";

    public static final class Rule {
        public final int type;
        public final String value; // only meaningful for RULE_CUSTOM

        public Rule(int type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class Profile {
        public final String id;
        public String name;
        public String mode;
        /** One of the {@code COLOR_*} tokens, or null for "no colour chosen". */
        public String color;
        /** Free-text user note, already sanitized; null for "no note". */
        public String note;
        /**
         * Wall-clock millis when this profile was last made active (Launch, or an
         * explicit switch). 0 means "never selected since this was recorded".
         */
        public long lastUsedAt;
        final Map<String, Rule> rules = new ArrayMap<>();

        Profile(String id, String name, String mode) {
            this.id = id;
            this.name = name;
            this.mode = mode;
        }
    }

    /** Result of {@link #importFromJson}: how many packages were added or updated. */
    public static final class ImportResult {
        public final int packagesImported;

        ImportResult(int packagesImported) {
            this.packagesImported = packagesImported;
        }
    }

    private static final class AppEntry {
        // LinkedHashMap so profile listing order matches creation order.
        final Map<String, Profile> profiles = new LinkedHashMap<>();
        String activeProfileId;
    }

    /** packageName -> AppEntry */
    private final Map<String, AppEntry> mApps = new ArrayMap<>();
    /** "packageName|profileId|key" -> generated value, for RULE_STATIC stability */
    private final Map<String, String> mGenerated = new HashMap<>();
    /** {@link #mGenerated} holds a change that is not on disk yet. */
    private boolean mGeneratedDirty;
    /** A flush is already queued on the IO thread; see {@link #saveGenerated}. */
    private boolean mGeneratedWriteQueued;

    /** Developer gate for the `adb shell cmd privacykit` mutating interface. */
    private boolean mAdbControlEnabled;
    private final AtomicFile mProfilesFile;
    private final AtomicFile mGeneratedFile;

    public PrivacyKitProfileStore(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        mProfilesFile = new AtomicFile(new File(dir, "profiles.xml"));
        mGeneratedFile = new AtomicFile(new File(dir, "generated.xml"));
    }

    public synchronized void load() {
        loadProfiles();
        loadGenerated();
    }

    // ---- active-profile rule access (unchanged external contract) -------------

    public synchronized boolean hasProfile(String packageName) {
        Profile p = activeProfileOrNull(packageName);
        return p != null && !p.rules.isEmpty();
    }

    public synchronized Rule getRule(String packageName, String key) {
        Profile p = activeProfileOrNull(packageName);
        return p != null ? p.rules.get(key) : null;
    }

    public synchronized void setRule(String packageName, String key, int type, String value) {
        if (type == PrivacyKitRuleResolver.RULE_REAL) {
            Profile p = activeProfileOrNull(packageName);
            if (p == null) {
                return;
            }
            p.rules.remove(key);
            mGenerated.remove(genKey(packageName, p.id, key));
            saveProfiles();
            saveGenerated();
            return;
        }
        Profile p = ensureActiveProfile(packageName);
        p.rules.put(key, new Rule(type, value));
        mGenerated.remove(genKey(packageName, p.id, key));
        saveProfiles();
        saveGenerated();
    }

    public synchronized void clearRule(String packageName, String key) {
        setRule(packageName, key, PrivacyKitRuleResolver.RULE_REAL, null);
    }

    /** Clears every rule on the *active* profile (does not delete the profile itself). */
    public synchronized void clearProfile(String packageName) {
        Profile p = activeProfileOrNull(packageName);
        if (p == null || p.rules.isEmpty()) {
            return;
        }
        for (String key : p.rules.keySet()) {
            mGenerated.remove(genKey(packageName, p.id, key));
        }
        p.rules.clear();
        saveProfiles();
        saveGenerated();
    }

    /** Packages whose active profile has at least one non-REAL rule configured. */
    public synchronized List<String> getConfiguredPackages() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, AppEntry> e : mApps.entrySet()) {
            Profile p = activeProfileOrNull(e.getKey());
            if (p != null && !p.rules.isEmpty()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    // ---- profile management (new) ----------------------------------------------

    /** Every package that has at least one Profile, i.e. has been "added" via the + flow. */
    public synchronized List<String> getManagedPackages() {
        return new ArrayList<>(mApps.keySet());
    }

    public synchronized List<String> listProfileIds(String packageName) {
        AppEntry e = mApps.get(packageName);
        return e != null ? new ArrayList<>(e.profiles.keySet()) : new ArrayList<>();
    }

    public synchronized String getActiveProfileId(String packageName) {
        AppEntry e = mApps.get(packageName);
        return e != null ? e.activeProfileId : null;
    }

    public synchronized String getProfileName(String packageName, String profileId) {
        Profile p = profileOrNull(packageName, profileId);
        return p != null ? p.name : null;
    }

    public synchronized String getProfileMode(String packageName, String profileId) {
        Profile p = profileOrNull(packageName, profileId);
        return p != null ? p.mode : null;
    }

    public synchronized void renameProfile(String packageName, String profileId, String newName) {
        Profile p = profileOrNull(packageName, profileId);
        if (p == null || newName == null || newName.isEmpty()) {
            return;
        }
        p.name = newName;
        saveProfiles();
    }

    public synchronized void setProfileMode(String packageName, String profileId, String mode) {
        Profile p = profileOrNull(packageName, profileId);
        if (p == null || !isValidMode(mode)) {
            return;
        }
        p.mode = mode;
        saveProfiles();
    }

    /** One of the {@code COLOR_*} tokens, or null if the profile has no colour -
     *  which is also what every profile stored before colours existed reads as. */
    public synchronized String getProfileColor(String packageName, String profileId) {
        Profile p = profileOrNull(packageName, profileId);
        return p != null ? p.color : null;
    }

    /**
     * Sets a profile's colour token. Null or "" clears it back to "no colour";
     * anything that is not one of the {@code COLOR_*} tokens is ignored and
     * leaves the current colour alone, exactly as {@link #setProfileMode}
     * ignores an unknown mode.
     */
    public synchronized void setProfileColor(String packageName, String profileId, String color) {
        Profile p = profileOrNull(packageName, profileId);
        if (p == null) {
            return;
        }
        if (color == null || color.isEmpty()) {
            p.color = null;
        } else if (isValidColor(color)) {
            p.color = color;
        } else {
            Slog.w(TAG, "Ignoring unknown profile colour token " + color);
            return;
        }
        saveProfiles();
    }

    /** The profile's free-text note, or null if it has none. */
    /** Millis when this profile was last made active, or 0 if never/unknown. */
    public synchronized long getProfileLastUsed(String packageName, String profileId) {
        Profile p = profileOrNull(packageName, profileId);
        return p != null ? p.lastUsedAt : 0L;
    }

    public synchronized String getProfileNote(String packageName, String profileId) {
        Profile p = profileOrNull(packageName, profileId);
        return p != null ? p.note : null;
    }

    /**
     * Sets a profile's free-text note. Null, empty or whitespace-only clears it;
     * anything over {@link #MAX_NOTE_LENGTH} characters is truncated to that
     * length (see {@link #sanitizeNote}), so what {@link #getProfileNote} hands
     * back afterwards is always exactly what was persisted.
     */
    public synchronized void setProfileNote(String packageName, String profileId, String note) {
        Profile p = profileOrNull(packageName, profileId);
        if (p == null) {
            return;
        }
        p.note = sanitizeNote(note);
        saveProfiles();
    }

    /** Creates a new profile for packageName. If it's the package's first profile, it
     *  becomes active immediately - this is how an app gets "added" via the + flow. */
    public synchronized String createProfile(String packageName, String name, String mode) {
        AppEntry e = mApps.computeIfAbsent(packageName, k -> new AppEntry());
        String id = UUID.randomUUID().toString();
        Profile p = new Profile(id, name, isValidMode(mode) ? mode : MODE_HYBRID);
        e.profiles.put(id, p);
        if (e.activeProfileId == null) {
            e.activeProfileId = id;
        }
        saveProfiles();
        return id;
    }

    /**
     * Creates a copy of an existing profile under a new id: same mode, colour,
     * note and rules (including each CUSTOM rule's literal value), new name.
     * Returns the new profile's id, or null if {@code profileId} does not exist.
     *
     * <p>The copy does <em>not</em> become active - the package has an active
     * profile already, since a source profile had to exist for the call to
     * succeed at all.
     *
     * <p>It also deliberately does not inherit the source's generated values.
     * Those are keyed by profile id, so every RULE_STATIC key on the copy mints
     * a fresh value on first read: two profiles of the same app are two
     * different identities, which is the entire point of having two. Copying
     * the cache would instead hand both profiles one shared identity and
     * silently link them - the opposite of what a privacy tool should default
     * to. A user who wants the same identity twice already has it: the source
     * profile.
     */
    public synchronized String cloneProfile(String packageName, String profileId, String newName) {
        AppEntry e = mApps.get(packageName);
        Profile source = e != null ? e.profiles.get(profileId) : null;
        if (source == null) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        Profile copy = new Profile(id,
                (newName != null && !newName.isEmpty()) ? newName : source.name, source.mode);
        copy.color = source.color;
        copy.note = source.note;
        for (Map.Entry<String, Rule> ruleEntry : source.rules.entrySet()) {
            Rule r = ruleEntry.getValue();
            copy.rules.put(ruleEntry.getKey(), new Rule(r.type, r.value));
        }
        e.profiles.put(id, copy);
        if (e.activeProfileId == null) {
            // Defensive, matching createProfile: an AppEntry should never hold
            // profiles with no active one, and if it somehow did, this is the
            // cheapest place to repair it.
            e.activeProfileId = id;
        }
        saveProfiles();
        return id;
    }

    public synchronized void setActiveProfile(String packageName, String profileId) {
        AppEntry e = mApps.get(packageName);
        if (e == null || !e.profiles.containsKey(profileId)) {
            return;
        }
        e.activeProfileId = profileId;
        Profile selected = e.profiles.get(profileId);
        if (selected != null) {
            selected.lastUsedAt = System.currentTimeMillis();
        }
        saveProfiles();
    }

    public synchronized void deleteProfile(String packageName, String profileId) {
        AppEntry e = mApps.get(packageName);
        if (e == null) {
            return;
        }
        Profile removed = e.profiles.remove(profileId);
        if (removed == null) {
            return;
        }
        // Colour and note need no sweep of their own: they live on the Profile
        // object that was just removed from the map, so they go with it. Only
        // the generated-value cache below is a side map keyed by
        // (pkg, profileId, key) and has to be cleaned up explicitly.
        for (String key : removed.rules.keySet()) {
            mGenerated.remove(genKey(packageName, profileId, key));
        }
        if (profileId.equals(e.activeProfileId)) {
            e.activeProfileId = e.profiles.isEmpty()
                    ? null : e.profiles.keySet().iterator().next();
        }
        if (e.profiles.isEmpty()) {
            mApps.remove(packageName);
        }
        saveProfiles();
        saveGenerated();
    }

    private static boolean isValidMode(String mode) {
        return MODE_ISOLATED.equals(mode) || MODE_HYBRID.equals(mode) || MODE_SHARED.equals(mode);
    }

    /** True if {@code color} is one of the {@code COLOR_*} tokens. */
    private static boolean isValidColor(String color) {
        if (color == null) {
            return false;
        }
        for (String token : COLOR_TOKENS) {
            if (token.equals(color)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes a user-supplied note: trims surrounding whitespace, maps null
     * and blank to null ("no note"), and truncates anything over
     * {@link #MAX_NOTE_LENGTH} characters instead of rejecting it.
     *
     * <p>Truncating rather than rejecting is the honest failure mode here: the
     * setter stores the shortened text and the getter reads back exactly that,
     * so the UI shows the user the note that actually exists rather than a
     * longer one it only believes it saved.
     */
    private static String sanitizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            Slog.w(TAG, "Truncating profile note from " + trimmed.length() + " to "
                    + MAX_NOTE_LENGTH + " chars");
            return trimmed.substring(0, MAX_NOTE_LENGTH);
        }
        return trimmed;
    }

    private Profile profileOrNull(String packageName, String profileId) {
        AppEntry e = mApps.get(packageName);
        return e != null ? e.profiles.get(profileId) : null;
    }

    private Profile activeProfileOrNull(String packageName) {
        AppEntry e = mApps.get(packageName);
        if (e == null || e.activeProfileId == null) {
            return null;
        }
        return e.profiles.get(e.activeProfileId);
    }

    /** Returns the active profile, creating a "Default" one first if the package has none. */
    private Profile ensureActiveProfile(String packageName) {
        AppEntry e = mApps.computeIfAbsent(packageName, k -> new AppEntry());
        if (e.activeProfileId == null) {
            String id = UUID.randomUUID().toString();
            e.profiles.put(id, new Profile(id, "Default", MODE_HYBRID));
            e.activeProfileId = id;
        }
        return e.profiles.get(e.activeProfileId);
    }

    // ---- generated-value cache (for RULE_STATIC), keyed per-profile -----------

    /**
     * Whether {@code value} is something this store is willing to freeze into a
     * profile.
     *
     * <p>A generated value is minted once and then frozen for the life of the
     * profile, so this cache is the one place where a placeholder could become
     * permanent, and a half-applied identity - some fields real, some fields
     * the literal text of an unresolved template - is strictly worse than no
     * spoof at all: it hands the app a value no device could ever report, which
     * is the class of tell this project exists to remove.
     *
     * <p>Rejects null, blank, and anything still carrying a template marker.
     * Nothing legitimate ever lands here with one: every generator output is
     * hex, decimal digits, a MAC, a UUID, a language tag, a time zone id or a
     * date. The marker check therefore costs nothing and only ever fires on a
     * hand-edited generated.xml, a restored file from another tool, or a future
     * generator that grows a templating step.
     */
    private static boolean isFreezableValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return !value.contains("${") && !value.contains("{{");
    }

    /**
     * The frozen RULE_STATIC value for (active profile of packageName, key),
     * minting and persisting one if this is the first read.
     *
     * <p>Mint-and-persist happens here, inside this object monitor, rather than
     * as a get / generate / put sequence in the caller. Two app processes
     * reading the same key at the same moment both saw null, both minted a
     * different value and both persisted: one of them returned a value that was
     * immediately overwritten, so that app saw one identity for that read and a
     * different one for every read afterwards. One critical section makes the
     * first mint the only mint.
     *
     * <p>Only mints when the active profile really carries a RULE_STATIC rule
     * for the key, so a read-only caller can never pollute the cache with a
     * value for a key that has no rule behind it.
     *
     * <p>Returns null - meaning "nothing cached, mint it yourself" - for the
     * keys whose generator needs the real underlying value as an input (see
     * {@link PrivacyKitIdentifierGenerator#needsRealValue}), which this store
     * does not have. {@link PrivacyKitRuleResolver} mints those with the real
     * value in hand and hands the result to {@link #putGeneratedValue} below,
     * which applies the same freeze gate.
     *
     * <p>A read that hits the cache - which is every read after the first -
     * touches no file at all, and a read that mints only marks the cache dirty:
     * the write itself is handed to the IO thread by {@link #saveGenerated}, so
     * no caller ever holds this monitor across disk IO.
     */
    public synchronized String getGeneratedValue(String packageName, String key) {
        Profile p = activeProfileOrNull(packageName);
        if (p == null || key == null) {
            return null;
        }
        final String cacheKey = genKey(packageName, p.id, key);
        final String cached = mGenerated.get(cacheKey);
        if (isFreezableValue(cached)) {
            return cached;
        }
        if (cached != null) {
            // A poisoned entry - empty, or a template that was never resolved.
            // Drop it rather than hand it to an app; the mint below replaces it.
            mGenerated.remove(cacheKey);
            saveGenerated();
        }
        final Rule rule = p.rules.get(key);
        if (rule == null || rule.type != PrivacyKitRuleResolver.RULE_STATIC) {
            return null;
        }
        if (PrivacyKitIdentifierGenerator.needsRealValue(key)) {
            return null;
        }
        String minted;
        try {
            minted = PrivacyKitIdentifierGenerator.generate(
                    key, PrivacyKitIdentifierGenerator.freshSeed(), null);
        } catch (RuntimeException e) {
            // Fail open: the resolver falls back to the real value for this
            // read and tries again on the next one.
            Slog.e(TAG, "Failed to mint a static value for " + packageName + "/" + key, e);
            return null;
        }
        if (!isFreezableValue(minted)) {
            Slog.w(TAG, "Refusing to freeze an unresolved value for "
                    + packageName + "/" + key);
            return null;
        }
        mGenerated.put(cacheKey, minted);
        saveGenerated();
        return minted;
    }

    /**
     * Freezes an externally minted value, for the keys
     * {@link #getGeneratedValue} cannot mint itself.
     *
     * <p>First writer wins: an entry that is already frozen is never
     * overwritten, which is the other half of the race described above. This
     * does not block a deliberate re-roll - {@link #setRule} drops the cache
     * entry first, which is how "Randomise" re-mints a STATIC value.
     */
    public synchronized void putGeneratedValue(String packageName, String key, String value) {
        Profile p = activeProfileOrNull(packageName);
        if (p == null || key == null) {
            return;
        }
        if (!isFreezableValue(value)) {
            Slog.w(TAG, "Refusing to freeze an unresolved value for "
                    + packageName + "/" + key);
            return;
        }
        final String cacheKey = genKey(packageName, p.id, key);
        if (isFreezableValue(mGenerated.get(cacheKey))) {
            return; // already frozen - see getGeneratedValue().
        }
        mGenerated.put(cacheKey, value);
        saveGenerated();
    }

    // ---- hot-path gate for the PackageManager install-time hook --------------

    /**
     * Whether any profile of any package carries a
     * {@link PrivacyKitKeys#KEY_FIRST_INSTALL_TIME} rule.
     *
     * <p>Exists so that {@code ComputerEngine#generatePackageInfo} - which runs
     * for every PackageInfo every app ever asks for, including whole
     * getInstalledPackages() sweeps - can decide in one volatile read that it
     * has nothing to do. Without it that path would have to look up the calling
     * package and enter the store monitor on every query on every device, and
     * almost no device has this rule set.
     *
     * <p>Deliberately conservative: true when the key appears on any profile,
     * active or not. A false positive only costs one resolveIdentifier() call
     * that returns the real value; a false negative would silently disable the
     * feature.
     */
    private static volatile boolean sAnyInstallTimeRule;

    /** @see #sAnyInstallTimeRule */
    public static boolean mayHaveInstallTimeRule() {
        return sAnyInstallTimeRule;
    }

    /**
     * Recomputes {@link #sAnyInstallTimeRule}. Called from the two choke points
     * every mutation and the boot load pass through, so it never has to be
     * remembered at an individual call site.
     */
    private void refreshHotPathFlags() {
        boolean any = false;
        outer:
        for (AppEntry entry : mApps.values()) {
            for (Profile profile : entry.profiles.values()) {
                if (profile.rules.containsKey(PrivacyKitKeys.KEY_FIRST_INSTALL_TIME)) {
                    any = true;
                    break outer;
                }
            }
        }
        sAnyInstallTimeRule = any;
    }

    private static String genKey(String packageName, String profileId, String key) {
        return packageName + "|" + profileId + "|" + key;
    }

    // ---- backup / restore (JSON) ------------------------------------------------

    private static final String JSON_PACKAGES = "packages";
    private static final String JSON_ACTIVE_PROFILE_ID = "activeProfileId";
    private static final String JSON_PROFILES = "profiles";
    private static final String JSON_ID = "id";
    private static final String JSON_NAME = "name";
    private static final String JSON_MODE = "mode";
    private static final String JSON_COLOR = "color";
    private static final String JSON_NOTE = "note";
    private static final String JSON_RULES = "rules";
    private static final String JSON_KEY = "key";
    private static final String JSON_TYPE = "type";
    private static final String JSON_VALUE = "value";

    /**
     * Serializes every package's every profile (id, name, mode, and its
     * rules as key/type/value triples) to a JSON string, for the Settings
     * Backup &amp; Restore feature:
     *
     * <pre>
     * {"packages": {"&lt;pkg&gt;": {"activeProfileId": "&lt;id&gt;",
     *     "profiles": [{"id": "&lt;id&gt;", "name": "Default", "mode": "hybrid",
     *         "color": "blue", "note": "throwaway shopping profile",
     *         "rules": [{"key": "android_id", "type": 1, "value": "..."}]}]}}}
     * </pre>
     *
     * <p>"color" and "note" are written only when the profile has them, so a
     * store with neither exports byte-for-byte what it exported before they
     * existed, and {@link #importFromJson} treats an absent one as "unset".
     */
    public synchronized String exportToJson() {
        try {
            JSONObject packages = new JSONObject();
            for (Map.Entry<String, AppEntry> pkgEntry : mApps.entrySet()) {
                AppEntry app = pkgEntry.getValue();
                JSONObject pkgObj = new JSONObject();
                if (app.activeProfileId != null) {
                    pkgObj.put(JSON_ACTIVE_PROFILE_ID, app.activeProfileId);
                }
                JSONArray profilesArr = new JSONArray();
                for (Profile p : app.profiles.values()) {
                    JSONObject profileObj = new JSONObject();
                    profileObj.put(JSON_ID, p.id);
                    profileObj.put(JSON_NAME, p.name);
                    profileObj.put(JSON_MODE, p.mode);
                    if (p.color != null) {
                        profileObj.put(JSON_COLOR, p.color);
                    }
                    if (p.note != null) {
                        profileObj.put(JSON_NOTE, p.note);
                    }
                    JSONArray rulesArr = new JSONArray();
                    for (Map.Entry<String, Rule> ruleEntry : p.rules.entrySet()) {
                        Rule r = ruleEntry.getValue();
                        JSONObject ruleObj = new JSONObject();
                        ruleObj.put(JSON_KEY, ruleEntry.getKey());
                        ruleObj.put(JSON_TYPE, r.type);
                        if (r.value != null) {
                            ruleObj.put(JSON_VALUE, r.value);
                        }
                        rulesArr.put(ruleObj);
                    }
                    profileObj.put(JSON_RULES, rulesArr);
                    profilesArr.put(profileObj);
                }
                pkgObj.put(JSON_PROFILES, profilesArr);
                packages.put(pkgEntry.getKey(), pkgObj);
            }
            JSONObject root = new JSONObject();
            root.put(JSON_PACKAGES, packages);
            return root.toString();
        } catch (JSONException e) {
            // Every value we put() here is a plain String/int already held
            // in memory - this should be unreachable - but fail safe with an
            // empty-but-valid export rather than crashing the caller.
            Slog.e(TAG, "Failed to build profiles export JSON", e);
            return "{}";
        }
    }

    /**
     * Parses a JSON string in the {@link #exportToJson()} format and applies
     * it to the store, then persists via {@link #saveProfiles()}.
     *
     * When {@code merge} is true, packages/profiles found in {@code json}
     * are added (if new) or overwritten (if an existing package/profile id
     * matches), while every package/profile NOT mentioned in {@code json} is
     * left completely untouched. When {@code merge} is false, all existing
     * profile data is discarded first and replaced entirely by {@code json}.
     *
     * Malformed top-level JSON (not parseable, or missing the "packages"
     * object) throws {@link IllegalArgumentException} so the caller can
     * report a clean error instead of crashing. Individual profile/rule
     * entries that are individually invalid (bad mode, bad rule type,
     * missing required field) are skipped rather than failing the whole
     * import, so one bad entry in a hand-edited backup file doesn't block
     * restoring the rest.
     *
     * <p>A profile's "color" and "note" are optional in both directions: a
     * backup taken before they existed simply has neither, and each one is
     * read back through the same validation the live setters use, so a
     * hand-edited backup carrying an unknown colour token or a 10 KB note
     * imports as "no colour" / a truncated note instead of poisoning the
     * store.
     */
    public synchronized ImportResult importFromJson(String json, boolean merge) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Malformed PrivacyKit profiles backup JSON", e);
        }
        JSONObject packages = root.optJSONObject(JSON_PACKAGES);
        if (packages == null) {
            throw new IllegalArgumentException(
                    "Malformed PrivacyKit profiles backup JSON: missing '" + JSON_PACKAGES + "'");
        }

        Map<String, AppEntry> imported = new LinkedHashMap<>();
        Iterator<String> pkgNames = packages.keys();
        while (pkgNames.hasNext()) {
            String pkgName = pkgNames.next();
            JSONObject pkgObj = packages.optJSONObject(pkgName);
            JSONArray profilesArr = pkgObj != null ? pkgObj.optJSONArray(JSON_PROFILES) : null;
            if (profilesArr == null) {
                continue;
            }
            AppEntry entry = new AppEntry();
            for (int i = 0; i < profilesArr.length(); i++) {
                JSONObject profileObj = profilesArr.optJSONObject(i);
                if (profileObj == null) {
                    continue;
                }
                String id = profileObj.optString(JSON_ID, null);
                String name = profileObj.optString(JSON_NAME, null);
                String mode = profileObj.optString(JSON_MODE, null);
                if (id == null || id.isEmpty() || name == null || !isValidMode(mode)) {
                    continue; // skip: invalid/incomplete profile entry
                }
                Profile p = new Profile(id, name, mode);
                // Absent in every backup taken before profile colours/notes
                // existed: optString() then hands back null, which is exactly
                // the "no colour / no note" default, so an old backup imports
                // cleanly rather than being skipped.
                String color = profileObj.optString(JSON_COLOR, null);
                p.color = isValidColor(color) ? color : null;
                p.note = sanitizeNote(profileObj.optString(JSON_NOTE, null));
                JSONArray rulesArr = profileObj.optJSONArray(JSON_RULES);
                if (rulesArr != null) {
                    for (int j = 0; j < rulesArr.length(); j++) {
                        JSONObject ruleObj = rulesArr.optJSONObject(j);
                        if (ruleObj == null) {
                            continue;
                        }
                        String key = ruleObj.optString(JSON_KEY, null);
                        int type = ruleObj.optInt(JSON_TYPE, -1);
                        String value = ruleObj.has(JSON_VALUE)
                                ? ruleObj.optString(JSON_VALUE, null) : null;
                        if (key == null || key.isEmpty()
                                || !PrivacyKitRuleResolver.isValidRuleType(type)) {
                            continue; // skip: invalid/incomplete rule entry
                        }
                        p.rules.put(key, new Rule(type, value));
                    }
                }
                entry.profiles.put(id, p);
            }
            if (!entry.profiles.isEmpty()) {
                String activeId = pkgObj.optString(JSON_ACTIVE_PROFILE_ID, null);
                entry.activeProfileId = (activeId != null && entry.profiles.containsKey(activeId))
                        ? activeId
                        : entry.profiles.keySet().iterator().next();
                imported.put(pkgName, entry);
            }
        }

        if (merge) {
            for (Map.Entry<String, AppEntry> e : imported.entrySet()) {
                String pkgName = e.getKey();
                AppEntry incoming = e.getValue();
                AppEntry existing = mApps.get(pkgName);
                if (existing == null) {
                    mApps.put(pkgName, incoming);
                    continue;
                }
                for (Map.Entry<String, Profile> pe : incoming.profiles.entrySet()) {
                    existing.profiles.put(pe.getKey(), pe.getValue());
                    // Rules may have changed under an existing profile id;
                    // drop any now-stale RULE_STATIC generated-value cache
                    // entries for it so they regenerate from the new rule.
                    for (String key : pe.getValue().rules.keySet()) {
                        mGenerated.remove(genKey(pkgName, pe.getKey(), key));
                    }
                }
                if (incoming.activeProfileId != null
                        && existing.profiles.containsKey(incoming.activeProfileId)) {
                    existing.activeProfileId = incoming.activeProfileId;
                } else if (existing.activeProfileId == null && !existing.profiles.isEmpty()) {
                    existing.activeProfileId = existing.profiles.keySet().iterator().next();
                }
            }
        } else {
            mApps.clear();
            mApps.putAll(imported);
            // Full replace invalidates any generated-value cache entries
            // that referenced profile ids from the discarded data.
            mGenerated.clear();
        }
        saveProfiles();
        saveGenerated();
        return new ImportResult(imported.size());
    }

    // ---- persistence -----------------------------------------------------------

    /**
     * Reads profiles.xml into memory.
     *
     * <p>The schema is compatible in both directions, and both directions rest
     * on the same XmlPullParser property: attributes are looked up by name, and
     * {@link org.xmlpull.v1.XmlPullParser#getAttributeValue} returns null for
     * one that is not present rather than throwing. So a file written before
     * profile colours and notes existed loads with both null - the same "unset"
     * state a freshly created profile has - and older code reading a file
     * written by this version never asks for the two attributes it does not
     * know about, leaving them inert. Nothing here enumerates the attribute set
     * or rejects unknown names, so no version stamp is needed on the file.
     *
     * <p>Both values are re-validated on the way in rather than trusted:
     * profiles.xml is root-owned, but it is also the thing a hand-restored or
     * hand-edited backup writes, and an unknown colour token would otherwise
     * reach the UI as a token it cannot map.
     */
    private void loadProfiles() {
        if (!mProfilesFile.getBaseFile().exists()) {
            return;
        }
        try (FileInputStream fis = mProfilesFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            String currentPackage = null;
            AppEntry currentEntry = null;
            String pendingActiveId = null;
            Profile currentProfile = null;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (type == XmlPullParser.START_TAG) {
                    if (TAG_PROFILES.equals(tag)) {
                        mAdbControlEnabled = parser.getAttributeBoolean(
                                null, ATTR_ADB_CONTROL, false);
                    } else if (TAG_PACKAGE.equals(tag)) {
                        currentPackage = parser.getAttributeValue(null, ATTR_NAME);
                        currentEntry = new AppEntry();
                        pendingActiveId = parser.getAttributeValue(null, ATTR_ACTIVE);
                        currentProfile = null;
                    } else if (TAG_PROFILE.equals(tag) && currentEntry != null) {
                        String id = parser.getAttributeValue(null, ATTR_ID);
                        String name = parser.getAttributeValue(null, ATTR_NAME);
                        String mode = parser.getAttributeValue(null, ATTR_MODE);
                        String color = parser.getAttributeValue(null, ATTR_COLOR);
                        String note = parser.getAttributeValue(null, ATTR_NOTE);
                        if (id != null) {
                            currentProfile = new Profile(id, name != null ? name : "Default",
                                    isValidMode(mode) ? mode : MODE_HYBRID);
                            currentProfile.color = isValidColor(color) ? color : null;
                            currentProfile.note = sanitizeNote(note);
                            currentProfile.lastUsedAt =
                                    parser.getAttributeLong(null, ATTR_LAST_USED, 0L);
                            currentEntry.profiles.put(id, currentProfile);
                        }
                    } else if (TAG_RULE.equals(tag) && currentProfile != null) {
                        String key = parser.getAttributeValue(null, ATTR_KEY);
                        int ruleType = parser.getAttributeInt(
                                null, ATTR_TYPE, PrivacyKitRuleResolver.RULE_REAL);
                        String value = parser.getAttributeValue(null, ATTR_VALUE);
                        if (key != null && PrivacyKitRuleResolver.isValidRuleType(ruleType)) {
                            currentProfile.rules.put(key, new Rule(ruleType, value));
                        }
                    }
                } else if (type == XmlPullParser.END_TAG && TAG_PACKAGE.equals(tag)) {
                    if (currentPackage != null && currentEntry != null
                            && !currentEntry.profiles.isEmpty()) {
                        currentEntry.activeProfileId =
                                (pendingActiveId != null
                                        && currentEntry.profiles.containsKey(pendingActiveId))
                                        ? pendingActiveId
                                        : currentEntry.profiles.keySet().iterator().next();
                        mApps.put(currentPackage, currentEntry);
                    }
                    currentPackage = null;
                    currentEntry = null;
                    currentProfile = null;
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to load profiles.xml, starting empty", e);
        }
        refreshHotPathFlags();
    }

    public synchronized boolean isAdbControlEnabled() {
        return mAdbControlEnabled;
    }

    public synchronized void setAdbControlEnabled(boolean enabled) {
        if (mAdbControlEnabled == enabled) {
            return;
        }
        mAdbControlEnabled = enabled;
        saveProfiles();
    }

    /** Snapshot of the active profile's set rules, for `cmd privacykit dump`. */
    public synchronized Map<String, Rule> getActiveRules(String packageName) {
        Profile p = activeProfileOrNull(packageName);
        return p != null
                ? new java.util.LinkedHashMap<>(p.rules)
                : java.util.Collections.emptyMap();
    }

    private void saveProfiles() {
        // Every mutating method funnels through here, so this is the one place
        // the PackageManager hot-path gate has to be kept up to date. Done
        // before the write, not after: the in-memory state is already current
        // and the flag must not depend on the file write succeeding.
        refreshHotPathFlags();
        FileOutputStream fos = null;
        try {
            fos = mProfilesFile.startWrite();
            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_PROFILES);
            if (mAdbControlEnabled) {
                out.attributeBoolean(null, ATTR_ADB_CONTROL, true);
            }
            for (Map.Entry<String, AppEntry> pkgEntry : mApps.entrySet()) {
                AppEntry app = pkgEntry.getValue();
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATTR_NAME, pkgEntry.getKey());
                if (app.activeProfileId != null) {
                    out.attribute(null, ATTR_ACTIVE, app.activeProfileId);
                }
                for (Profile p : app.profiles.values()) {
                    out.startTag(null, TAG_PROFILE);
                    out.attribute(null, ATTR_ID, p.id);
                    out.attribute(null, ATTR_NAME, p.name);
                    out.attribute(null, ATTR_MODE, p.mode);
                    // Written only when set, so a store whose profiles have
                    // neither serializes exactly the same bytes it did before
                    // these two attributes existed.
                    if (p.color != null) {
                        out.attribute(null, ATTR_COLOR, p.color);
                    }
                    if (p.note != null) {
                        out.attribute(null, ATTR_NOTE, p.note);
                    }
                    if (p.lastUsedAt > 0L) {
                        out.attributeLong(null, ATTR_LAST_USED, p.lastUsedAt);
                    }
                    for (Map.Entry<String, Rule> ruleEntry : p.rules.entrySet()) {
                        Rule r = ruleEntry.getValue();
                        out.startTag(null, TAG_RULE);
                        out.attribute(null, ATTR_KEY, ruleEntry.getKey());
                        out.attributeInt(null, ATTR_TYPE, r.type);
                        if (r.value != null) {
                            out.attribute(null, ATTR_VALUE, r.value);
                        }
                        out.endTag(null, TAG_RULE);
                    }
                    out.endTag(null, TAG_PROFILE);
                }
                out.endTag(null, TAG_PACKAGE);
            }
            out.endTag(null, TAG_PROFILES);
            out.endDocument();
            mProfilesFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save profiles.xml", e);
            if (fos != null) {
                mProfilesFile.failWrite(fos);
            }
        }
    }

    private void loadGenerated() {
        if (!mGeneratedFile.getBaseFile().exists()) {
            return;
        }
        try (FileInputStream fis = mGeneratedFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                if (TAG_ENTRY.equals(parser.getName())) {
                    String pkg = parser.getAttributeValue(null, ATTR_PKG);
                    String profileId = parser.getAttributeValue(null, ATTR_PROFILE_ID);
                    String key = parser.getAttributeValue(null, ATTR_KEY);
                    String value = parser.getAttributeValue(null, ATTR_VALUE);
                    // isFreezableValue() rather than a null check: this file
                    // is the one an operator or a restore can hand-write, and a
                    // placeholder read back out of it would be applied to a
                    // real app. See getGeneratedValue().
                    if (pkg != null && profileId != null && key != null
                            && isFreezableValue(value)) {
                        mGenerated.put(genKey(pkg, profileId, key), value);
                    }
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to load generated.xml, starting empty", e);
        }
    }

    /**
     * Marks the generated-value cache dirty and schedules at most one write of
     * generated.xml on the shared IO thread. Callers hold this monitor; the
     * disk IO deliberately does not.
     *
     * <p>This used to serialise the whole file inline, and one of its callers
     * is {@link #getGeneratedValue} - i.e. a <em>read</em> that happened to be
     * the first one for a key. That put a full AtomicFile rewrite (create a
     * temp file, serialise every generated entry on the device, fsync, rename)
     * on a system_server binder thread during app startup, inside the one lock
     * every other PrivacyKit call has to take. Now the map mutation stays under
     * the lock, which is what the first-mint-wins race needs, and only the file
     * write moves off it.
     *
     * <p>Crash-safety is intact where it matters. AtomicFile still makes the
     * file all-or-nothing, and every flush runs on a single handler thread, so
     * two writes can never interleave or land out of order. The one thing the
     * change costs is a short window in which a system_server crash loses the
     * newest mint; the app then sees a freshly minted value on its next read
     * instead of the one it briefly saw - the same outcome as the deliberate
     * re-roll that {@link #setRule} already performs, not a corrupt file.
     */
    private void saveGenerated() {
        mGeneratedDirty = true;
        if (mGeneratedWriteQueued) {
            return;
        }
        mGeneratedWriteQueued = true;
        try {
            IoThread.getHandler().post(this::flushGenerated);
        } catch (RuntimeException e) {
            // No usable IO thread; should not happen inside system_server, but
            // losing a frozen identity is worse than a slow write, so fall back
            // to writing inline (this monitor is reentrant).
            mGeneratedWriteQueued = false;
            Slog.w(TAG, "No IO thread for generated.xml, writing inline", e);
            flushGenerated();
        }
    }

    /**
     * Runs on the IO thread: snapshots the cache under the monitor, then writes
     * outside it. A failed write leaves the cache dirty so the next mutation
     * retries rather than silently dropping the entry.
     */
    private void flushGenerated() {
        final Map<String, String> snapshot;
        synchronized (this) {
            mGeneratedWriteQueued = false;
            if (!mGeneratedDirty) {
                return;
            }
            mGeneratedDirty = false;
            snapshot = new HashMap<>(mGenerated);
        }
        if (!writeGeneratedFile(snapshot)) {
            synchronized (this) {
                mGeneratedDirty = true;
            }
        }
    }

    /**
     * Serialises {@code generated} to generated.xml. Never throws; returns
     * false if the file was left untouched.
     */
    private boolean writeGeneratedFile(Map<String, String> generated) {
        FileOutputStream fos = null;
        try {
            fos = mGeneratedFile.startWrite();
            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_GENERATED);
            for (Map.Entry<String, String> e : generated.entrySet()) {
                String[] parts = e.getKey().split("\\|", 3);
                if (parts.length != 3) continue;
                out.startTag(null, TAG_ENTRY);
                out.attribute(null, ATTR_PKG, parts[0]);
                out.attribute(null, ATTR_PROFILE_ID, parts[1]);
                out.attribute(null, ATTR_KEY, parts[2]);
                out.attribute(null, ATTR_VALUE, e.getValue());
                out.endTag(null, TAG_ENTRY);
            }
            out.endTag(null, TAG_GENERATED);
            out.endDocument();
            mGeneratedFile.finishWrite(fos);
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save generated.xml", e);
            if (fos != null) {
                mGeneratedFile.failWrite(fos);
            }
            return false;
        }
    }
}
