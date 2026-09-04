/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.privacykit;

import android.os.Binder;
import android.os.Process;
import android.os.ShellCommand;
import android.privacykit.IPrivacyKitManager;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * `adb shell cmd privacykit ...` - a gated control surface over PrivacyKit-Native
 * so tests (and tooling) don't have to drive the Compose UI.
 *
 * <p>READ subcommands work whenever invoked from adb; MUTATING subcommands
 * additionally require the developer gate (Settings &gt; PrivacyKit &gt; Settings
 * &gt; Developer &gt; ADB control), which can only be flipped from that UI - never
 * from here. See {@link PrivacyKitShellCommand} usage in
 * {@link PrivacyKitService}.
 */
class PrivacyKitShellCommand extends ShellCommand {

    private final PrivacyKitService mService;
    private final IPrivacyKitManager mStub;

    /**
     * Set by the global {@code --json} / {@code -j} flag. Strictly opt-in: with
     * it unset every subcommand prints exactly what it has always printed.
     */
    private boolean mJson;

    /** The subcommand being run, so an error object can name it. */
    private String mCmd;

    PrivacyKitShellCommand(PrivacyKitService service, IPrivacyKitManager stub) {
        mService = service;
        mStub = stub;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        // Global flags are consumed here, ahead of the subcommand, so that they
        // can never be swallowed as one of its positional arguments. Reading a
        // flag is not a privileged operation, so this happens before the uid
        // check - which lets even the refusal come back machine-readable.
        String sub = cmd;
        while ("--json".equals(sub) || "-j".equals(sub)) {
            mJson = true;
            sub = getNextArg();
            if (sub == null) {
                return handleDefaultCommands(null);
            }
        }
        mCmd = sub;
        final PrintWriter pw = getOutPrintWriter();
        final int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            return fail(pw, "cmd privacykit is only usable from adb shell/root", "not_shell");
        }
        try {
            switch (sub) {
                case "status":         return runStatus(pw);
                case "list":           return runList(pw);
                case "active":         return runActive(pw);
                case "get-rule":       return runGetRule(pw);
                case "resolve":        return runResolve(pw);
                case "dump":           return runDump(pw);
                case "mcp-describe":   return runMcpDescribe(pw);
                case "set-rule":       return gated(pw) ? runSetRule(pw) : -1;
                case "clear-rule":     return gated(pw) ? runClearRule(pw) : -1;
                case "create-profile": return gated(pw) ? runCreateProfile(pw) : -1;
                case "set-active":     return gated(pw) ? runSetActive(pw) : -1;
                case "delete-profile": return gated(pw) ? runDeleteProfile(pw) : -1;
                default:
                    if (mJson && !"help".equals(sub) && !"-h".equals(sub)) {
                        return fail(pw, "Unknown command: " + sub, "unknown_command");
                    }
                    return handleDefaultCommands(sub);
            }
        } catch (IllegalArgumentException e) {
            return fail(pw, String.valueOf(e.getMessage()), "invalid_argument");
        } catch (Exception e) {
            return fail(pw, String.valueOf(e), "error");
        }
    }

    /**
     * Reports a failure: the original {@code Error: ...} line on stderr in the
     * default mode, a parseable object on stdout in JSON mode. Always -1, as
     * before.
     */
    private int fail(PrintWriter pw, String message, String code) {
        if (!mJson) {
            getErrPrintWriter().println("Error: " + message);
            return -1;
        }
        final StringBuilder sb = new StringBuilder("{");
        appendRaw(sb, "ok", "false");
        appendStr(sb, "command", mCmd);
        appendStr(sb, "error_code", code);
        appendStr(sb, "error", message);
        sb.append("}");
        pw.println(sb);
        return -1;
    }

    /**
     * Mutating commands require the developer gate. This is the security
     * boundary of the whole surface, and JSON mode does not move it: the gate is
     * consulted on exactly the same commands as before, only the refusal is
     * rendered differently.
     */
    private boolean gated(PrintWriter pw) {
        if (mService.isAdbControlEnabledInternal()) {
            return true;
        }
        final String message = "ADB control is OFF. Enable it in Settings > PrivacyKit > "
                + "Settings > Developer > \"ADB control\" before mutating profiles.";
        if (!mJson) {
            getErrPrintWriter().println("Error: " + message);
            return false;
        }
        final StringBuilder sb = new StringBuilder("{");
        appendRaw(sb, "ok", "false");
        appendStr(sb, "command", mCmd);
        appendStr(sb, "error_code", "adb_control_disabled");
        appendStr(sb, "error", message);
        sb.append("}");
        pw.println(sb);
        return false;
    }

    private static int parseRuleType(String s) {
        switch (s.toLowerCase()) {
            case "real": case "0":                         return PrivacyKitRuleResolver.RULE_REAL;
            case "static": case "1":                       return PrivacyKitRuleResolver.RULE_STATIC;
            case "per_launch": case "per-launch": case "2": return PrivacyKitRuleResolver.RULE_PER_LAUNCH;
            case "daily": case "3":                        return PrivacyKitRuleResolver.RULE_DAILY;
            case "custom": case "4":                       return PrivacyKitRuleResolver.RULE_CUSTOM;
            case "empty": case "5":                        return PrivacyKitRuleResolver.RULE_EMPTY;
            default: throw new IllegalArgumentException(
                    "Unknown rule type '" + s + "' (real|static|per_launch|daily|custom|empty or 0-5)");
        }
    }

    private static String ruleTypeName(int t) {
        switch (t) {
            case PrivacyKitRuleResolver.RULE_REAL:       return "real";
            case PrivacyKitRuleResolver.RULE_STATIC:     return "static";
            case PrivacyKitRuleResolver.RULE_PER_LAUNCH: return "per_launch";
            case PrivacyKitRuleResolver.RULE_DAILY:      return "daily";
            case PrivacyKitRuleResolver.RULE_CUSTOM:     return "custom";
            case PrivacyKitRuleResolver.RULE_EMPTY:      return "empty";
            default: return "?(" + t + ")";
        }
    }

    // ---- JSON emission ---------------------------------------------------------
    //
    // Hand-rolled rather than org.json: key order stays deterministic (JSONObject
    // is HashMap-backed), no checked JSONException leaks into every call site, and
    // nothing new is pulled onto the services.core classpath. Every string that
    // reaches the output goes through appendJsonString(), so a rule value holding
    // a quote, a backslash or a control character cannot malform the document.

    /** Appends {@code s} as a JSON string literal, or the bare {@code null} literal. */
    private static void appendJsonString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append("\"");
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    // Control characters and DEL must be escaped. U+2028 / U+2029
                    // are legal in JSON but illegal inside a JavaScript string
                    // literal, so escape them too and stay safe for JS consumers.
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                        final String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            sb.append("0");
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
    }

    /** Opens the next element of an array or object, inserting the comma if needed. */
    private static void nextElement(StringBuilder sb) {
        final char last = sb.charAt(sb.length() - 1);
        if (last != '{' && last != '[') {
            sb.append(",");
        }
    }

    /** Writes {@code "key":}, comma-separated from whatever precedes it. */
    private static void appendKey(StringBuilder sb, String key) {
        nextElement(sb);
        appendJsonString(sb, key);
        sb.append(":");
    }

    /** {@code "key":"value"}, or {@code "key":null}. */
    private static void appendStr(StringBuilder sb, String key, String value) {
        appendKey(sb, key);
        appendJsonString(sb, value);
    }

    /** {@code "key":<raw>}, for values that are already JSON (numbers, booleans). */
    private static void appendRaw(StringBuilder sb, String key, String raw) {
        appendKey(sb, key);
        sb.append(raw);
    }

    /** {@code "key":["a","b"]}. */
    private static void appendStrArray(StringBuilder sb, String key, List<String> values) {
        appendKey(sb, key);
        sb.append("[");
        for (String v : values) {
            nextElement(sb);
            appendJsonString(sb, v);
        }
        sb.append("]");
    }

    /** Opens a success object, pre-filled with {@code ok} and {@code command}. */
    private StringBuilder beginOk() {
        final StringBuilder sb = new StringBuilder("{");
        appendRaw(sb, "ok", "true");
        appendStr(sb, "command", mCmd);
        return sb;
    }

    /** Closes and prints an object opened by {@link #beginOk()}. */
    private static int end(PrintWriter pw, StringBuilder sb) {
        sb.append("}");
        pw.println(sb);
        return 0;
    }

    // ---- read subcommands ------------------------------------------------------

    private int runStatus(PrintWriter pw) {
        final boolean enabled = mService.isAdbControlEnabledInternal();
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendRaw(sb, "adb_control", Boolean.toString(enabled));
            appendRaw(sb, "mutating_requires_adb_control", "true");
            return end(pw, sb);
        }
        pw.println("adb_control=" + (enabled ? "enabled" : "disabled"));
        pw.println("(mutating subcommands require adb_control=enabled; toggle it in Settings)");
        return 0;
    }

    private int runList(PrintWriter pw) throws Exception {
        String pkg = getNextArg();
        if (pkg == null) {
            List<String> pkgs = mStub.getManagedPackages();
            if (mJson) {
                final StringBuilder sb = beginOk();
                appendStr(sb, "scope", "packages");
                appendRaw(sb, "count", Integer.toString(pkgs.size()));
                appendStrArray(sb, "packages", pkgs);
                return end(pw, sb);
            }
            pw.println("managed packages (" + pkgs.size() + "):");
            for (String p : pkgs) {
                pw.println("  " + p);
            }
            return 0;
        }
        List<String> ids = mStub.listProfileIds(pkg);
        String active = mStub.getActiveProfileId(pkg);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "scope", "profiles");
            appendStr(sb, "package", pkg);
            appendStr(sb, "active_profile_id", active);
            appendRaw(sb, "count", Integer.toString(ids.size()));
            appendKey(sb, "profiles");
            sb.append("[");
            for (String id : ids) {
                nextElement(sb);
                sb.append("{");
                appendStr(sb, "id", id);
                appendStr(sb, "name", mStub.getProfileName(pkg, id));
                appendStr(sb, "mode", mStub.getProfileMode(pkg, id));
                appendRaw(sb, "active", Boolean.toString(id.equals(active)));
                sb.append("}");
            }
            sb.append("]");
            return end(pw, sb);
        }
        pw.println(pkg + " profiles (" + ids.size() + "):");
        for (String id : ids) {
            pw.println("  " + id + "  name=" + mStub.getProfileName(pkg, id)
                    + "  mode=" + mStub.getProfileMode(pkg, id)
                    + (id.equals(active) ? "  [ACTIVE]" : ""));
        }
        return 0;
    }

    private int runActive(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String id = mStub.getActiveProfileId(pkg);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendRaw(sb, "managed", Boolean.toString(id != null));
            appendStr(sb, "active_profile_id", id);
            appendStr(sb, "name", id == null ? null : mStub.getProfileName(pkg, id));
            appendStr(sb, "mode", id == null ? null : mStub.getProfileMode(pkg, id));
            return end(pw, sb);
        }
        if (id == null) {
            pw.println(pkg + ": not managed (no profile)");
            return 0;
        }
        pw.println(pkg + " active=" + id + " name=" + mStub.getProfileName(pkg, id)
                + " mode=" + mStub.getProfileMode(pkg, id));
        return 0;
    }

    private int runGetRule(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String key = getNextArgRequired();
        int type = mStub.getRuleType(pkg, key);
        String value = mStub.getRuleValue(pkg, key);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "key", key);
            appendRaw(sb, "type_id", Integer.toString(type));
            appendStr(sb, "type", ruleTypeName(type));
            appendStr(sb, "value", value);
            return end(pw, sb);
        }
        pw.println(pkg + "/" + key + " type=" + ruleTypeName(type)
                + (value != null ? " value=" + value : ""));
        return 0;
    }

    private int runResolve(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String key = getNextArgRequired();
        String real = getNextArg();
        if (real == null) {
            real = "<real:" + key + ">";
        }
        String resolved = mService.resolveForShell(pkg, key, real);
        boolean changed = resolved == null ? real != null : !resolved.equals(real);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "key", key);
            appendStr(sb, "real", real);
            appendStr(sb, "resolved", resolved);
            appendRaw(sb, "spoofed", Boolean.toString(changed));
            return end(pw, sb);
        }
        pw.println(pkg + "/" + key + " resolved=" + resolved
                + " (real=" + real + ") " + (changed ? "[SPOOFED]" : "[unchanged]"));
        return 0;
    }

    private int runDump(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String active = mStub.getActiveProfileId(pkg);
        if (active == null) {
            if (mJson) {
                final StringBuilder sb = beginOk();
                appendStr(sb, "package", pkg);
                appendRaw(sb, "managed", "false");
                appendStr(sb, "active_profile_id", null);
                appendRaw(sb, "count", "0");
                appendRaw(sb, "rules", "[]");
                return end(pw, sb);
            }
            pw.println(pkg + ": not managed (no profile)");
            return 0;
        }
        Map<String, PrivacyKitProfileStore.Rule> rules = mService.activeRulesForShell(pkg);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendRaw(sb, "managed", "true");
            appendStr(sb, "active_profile_id", active);
            appendStr(sb, "profile_name", mStub.getProfileName(pkg, active));
            appendRaw(sb, "count", Integer.toString(rules.size()));
            appendKey(sb, "rules");
            sb.append("[");
            for (Map.Entry<String, PrivacyKitProfileStore.Rule> e : rules.entrySet()) {
                PrivacyKitProfileStore.Rule r = e.getValue();
                nextElement(sb);
                sb.append("{");
                appendStr(sb, "key", e.getKey());
                appendRaw(sb, "type_id", Integer.toString(r.type));
                appendStr(sb, "type", ruleTypeName(r.type));
                appendStr(sb, "value", r.value);
                // Resolved against the same "<real>" placeholder the human output
                // uses, so this never emits a genuine on-device identifier value.
                appendStr(sb, "resolved", mService.resolveForShell(pkg, e.getKey(), "<real>"));
                appendStr(sb, "resolved_from", "<real>");
                sb.append("}");
            }
            sb.append("]");
            return end(pw, sb);
        }
        pw.println(pkg + " active profile " + active + " ("
                + mStub.getProfileName(pkg, active) + "), " + rules.size() + " rule(s):");
        for (Map.Entry<String, PrivacyKitProfileStore.Rule> e : rules.entrySet()) {
            PrivacyKitProfileStore.Rule r = e.getValue();
            String resolved = mService.resolveForShell(pkg, e.getKey(), "<real>");
            pw.println("  " + e.getKey() + "  type=" + ruleTypeName(r.type)
                    + (r.value != null ? " value=" + r.value : "")
                    + "  -> " + resolved);
        }
        return 0;
    }

    // ---- mutating subcommands (gated) ------------------------------------------

    private int runSetRule(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String key = getNextArgRequired();
        int type = parseRuleType(getNextArgRequired());
        String value = getNextArg(); // required only for custom; validated by service
        mStub.setIdentifierRule(pkg, key, type, value);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "key", key);
            appendRaw(sb, "type_id", Integer.toString(type));
            appendStr(sb, "type", ruleTypeName(type));
            appendStr(sb, "value", value);
            return end(pw, sb);
        }
        pw.println("OK set " + pkg + "/" + key + " -> " + ruleTypeName(type)
                + (value != null ? " (" + value + ")" : ""));
        return 0;
    }

    private int runClearRule(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String key = getNextArgRequired();
        mStub.clearIdentifierRule(pkg, key);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "key", key);
            return end(pw, sb);
        }
        pw.println("OK cleared " + pkg + "/" + key);
        return 0;
    }

    private int runCreateProfile(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String name = getNextArgRequired();
        String mode = getNextArg(); // null -> service default
        String id = mStub.createProfile(pkg, name, mode);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "profile_id", id);
            appendStr(sb, "name", name);
            appendStr(sb, "mode_requested", mode);
            appendStr(sb, "mode", id == null ? null : mStub.getProfileMode(pkg, id));
            return end(pw, sb);
        }
        pw.println("OK created " + pkg + " profile id=" + id + " name=" + name);
        return 0;
    }

    private int runSetActive(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String id = getNextArgRequired();
        mStub.setActiveProfile(pkg, id);
        String now = mStub.getActiveProfileId(pkg);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "requested_profile_id", id);
            appendStr(sb, "active_profile_id", now);
            appendRaw(sb, "applied", Boolean.toString(id.equals(now)));
            return end(pw, sb);
        }
        pw.println("OK active=" + now);
        return 0;
    }

    private int runDeleteProfile(PrintWriter pw) throws Exception {
        String pkg = getNextArgRequired();
        String id = getNextArgRequired();
        mStub.deleteProfile(pkg, id);
        if (mJson) {
            final StringBuilder sb = beginOk();
            appendStr(sb, "package", pkg);
            appendStr(sb, "profile_id", id);
            return end(pw, sb);
        }
        pw.println("OK deleted " + pkg + "/" + id);
        return 0;
    }

    // ---- machine-readable description ------------------------------------------

    private static final String[][] NO_ARGS = new String[0][];

    private static final String[] ARG_PKG =
            {"PKG", "string", "true", "Application package name to act on."};
    private static final String[] ARG_KEY =
            {"KEY", "string", "true", "Identifier name; see key_catalog_note."};

    /**
     * {@code mcp-describe} - a JSON description of this whole command surface so
     * an agent can drive it without scraping the human help text.
     *
     * <p>Static schema only. It names no package, no profile, no rule and no
     * identifier value. The single piece of live state it reports is the
     * ADB-control gate, which the ungated {@code status} subcommand already
     * exposes. Being a read subcommand it moves nothing: every mutating command
     * listed here still goes through {@link #gated(PrintWriter)} when invoked.
     */
    private int runMcpDescribe(PrintWriter pw) {
        final StringBuilder sb = new StringBuilder("{");
        appendRaw(sb, "ok", "true");
        appendStr(sb, "command", "mcp-describe");
        appendRaw(sb, "schema_version", "1");
        appendStr(sb, "service", "privacykit");
        appendStr(sb, "invocation", "adb shell cmd privacykit [--json] SUBCOMMAND [ARGS...]");
        appendStr(sb, "description", "Per-app identity spoofing (PrivacyKit-Native): inspect "
                + "and configure which value a given application reads for a given device "
                + "identifier, and manage the named profiles those rules live on.");

        appendKey(sb, "json_mode");
        sb.append("{");
        appendStr(sb, "flag", "--json");
        appendStrArray(sb, "aliases", List.of("-j"));
        appendStr(sb, "position", "before the subcommand");
        appendRaw(sb, "default", "false");
        appendStr(sb, "note", "Opt-in. Without the flag every subcommand keeps its original "
                + "human-readable output. The flag must precede the subcommand so that it "
                + "can never be mistaken for a positional argument. mcp-describe always "
                + "emits JSON and does not need the flag.");
        appendStr(sb, "success_shape", "{\"ok\":true,\"command\":<string>,...}");
        appendStr(sb, "error_shape",
                "{\"ok\":false,\"command\":<string>,\"error_code\":<string>,\"error\":<string>}");
        appendStrArray(sb, "error_codes", List.of(
                "not_shell", "unknown_command", "invalid_argument",
                "adb_control_disabled", "error"));
        appendStr(sb, "exit_code", "0 on success, -1 whenever an error object is emitted.");
        sb.append("}");

        appendKey(sb, "access");
        sb.append("{");
        appendStr(sb, "caller", "Every subcommand requires the calling uid to be shell or root, "
                + "i.e. an adb session. There is no other entry point into this surface.");
        appendKey(sb, "gate");
        sb.append("{");
        appendStr(sb, "name", "adb_control");
        appendRaw(sb, "enabled", Boolean.toString(mService.isAdbControlEnabledInternal()));
        appendStr(sb, "error_code", "adb_control_disabled");
        appendStr(sb, "enable_via",
                "Settings > PrivacyKit > Settings > Developer > \"ADB control\"");
        appendStr(sb, "note", "Required by every subcommand whose requires_adb_control is true. "
                + "It can only be flipped from that Settings screen by a privileged caller, "
                + "never from this shell surface, so an adb session cannot grant itself write "
                + "access.");
        sb.append("}");
        sb.append("}");

        appendKey(sb, "enums");
        sb.append("{");
        appendStrArray(sb, "rule_type",
                List.of("real", "static", "per_launch", "daily", "custom", "empty"));
        appendStr(sb, "rule_type_note", "Accepted either by name (per_launch is also spelled "
                + "per-launch) or as the numeric id 0-5 in the order listed. custom is the "
                + "only type that takes a VALUE.");
        appendStrArray(sb, "profile_mode", List.of("isolated", "hybrid", "shared"));
        sb.append("}");

        appendStr(sb, "key_catalog_note", "KEY is a free-form identifier name, for example "
                + "android_id, serial, imei, build_fingerprint or wifi_mac. The authoritative "
                + "catalog is android.privacykit.PrivacyKitKeys. It is deliberately not "
                + "enumerated here: the platform exposes no single machine-readable list of "
                + "it, and a hand-copied list would silently drift. Use \"dump PKG\" to see "
                + "the keys a package already has rules for.");

        appendKey(sb, "commands");
        sb.append("[");
        appendCommand(sb, "status", false,
                "Report whether the ADB-control gate is currently on.",
                NO_ARGS,
                new String[][] {
                    {"adb_control", "boolean"},
                    {"mutating_requires_adb_control", "boolean"},
                });
        appendCommand(sb, "list", false,
                "With no argument, list every package managed by PrivacyKit. With PKG, list "
                + "that package profiles and mark the active one.",
                new String[][] {
                    {"PKG", "string", "false",
                        "Package to list profiles for; omit for the managed-package list."},
                },
                new String[][] {
                    {"scope", "string, packages or profiles"},
                    {"count", "integer"},
                    {"packages", "array of string, when scope=packages"},
                    {"package", "string, when scope=profiles"},
                    {"active_profile_id", "string or null, when scope=profiles"},
                    {"profiles", "array of {id:string, name:string, mode:string, "
                        + "active:boolean}, when scope=profiles"},
                });
        appendCommand(sb, "active", false,
                "Report the active profile of PKG.",
                new String[][] {ARG_PKG},
                new String[][] {
                    {"package", "string"},
                    {"managed", "boolean"},
                    {"active_profile_id", "string or null"},
                    {"name", "string or null"},
                    {"mode", "string or null"},
                });
        appendCommand(sb, "get-rule", false,
                "Report the rule configured for KEY on the active profile of PKG.",
                new String[][] {ARG_PKG, ARG_KEY},
                new String[][] {
                    {"package", "string"},
                    {"key", "string"},
                    {"type_id", "integer"},
                    {"type", "string, one of rule_type"},
                    {"value", "string or null, set only for the custom type"},
                });
        appendCommand(sb, "resolve", false,
                "Show what PKG would actually read for KEY, given a caller-supplied stand-in "
                + "for the real value. Read-only: it resolves, it does not configure.",
                new String[][] {
                    ARG_PKG, ARG_KEY,
                    {"REAL", "string", "false", "Stand-in for the real value. Defaults to a "
                        + "placeholder, so nothing genuine is read or printed."},
                },
                new String[][] {
                    {"package", "string"},
                    {"key", "string"},
                    {"real", "string, echoes the REAL argument"},
                    {"resolved", "string or null"},
                    {"spoofed", "boolean, true when resolved differs from real"},
                });
        appendCommand(sb, "dump", false,
                "List every rule set on the active profile of PKG together with what each one "
                + "resolves to. Resolution uses a placeholder, never a real identifier.",
                new String[][] {ARG_PKG},
                new String[][] {
                    {"package", "string"},
                    {"managed", "boolean"},
                    {"active_profile_id", "string or null"},
                    {"profile_name", "string or null"},
                    {"count", "integer"},
                    {"rules", "array of {key:string, type_id:integer, type:string, "
                        + "value:string or null, resolved:string or null, "
                        + "resolved_from:string}"},
                });
        appendCommand(sb, "mcp-describe", false,
                "This document. Always JSON; the --json flag is not required.",
                NO_ARGS,
                new String[][] {
                    {"schema_version", "integer"},
                    {"json_mode", "object"},
                    {"access", "object, caller requirement plus the adb_control gate"},
                    {"enums", "object"},
                    {"commands", "array of command descriptors"},
                });
        appendCommand(sb, "set-rule", true,
                "Set the rule for KEY on the active profile of PKG, creating a Default profile "
                + "first if the package has none yet.",
                new String[][] {
                    ARG_PKG, ARG_KEY,
                    {"TYPE", "string, one of rule_type", "true",
                        "Rule type, by name or by numeric id."},
                    {"VALUE", "string", "false", "Literal value. Required for the custom type "
                        + "and ignored otherwise; the service validates it."},
                },
                new String[][] {
                    {"package", "string"},
                    {"key", "string"},
                    {"type_id", "integer"},
                    {"type", "string, one of rule_type"},
                    {"value", "string or null"},
                });
        appendCommand(sb, "clear-rule", true,
                "Revert the rule for KEY on the active profile of PKG back to real.",
                new String[][] {ARG_PKG, ARG_KEY},
                new String[][] {
                    {"package", "string"},
                    {"key", "string"},
                });
        appendCommand(sb, "create-profile", true,
                "Create a new named profile for PKG. It becomes active immediately only if the "
                + "package had no profile before.",
                new String[][] {
                    ARG_PKG,
                    {"NAME", "string", "true", "Display name for the new profile."},
                    {"MODE", "string, one of profile_mode", "false",
                        "Defaults to the service default when omitted."},
                },
                new String[][] {
                    {"package", "string"},
                    {"profile_id", "string"},
                    {"name", "string"},
                    {"mode_requested", "string or null"},
                    {"mode", "string or null, the mode actually stored"},
                });
        appendCommand(sb, "set-active", true,
                "Switch the active profile of PKG. A profile id that does not exist is a no-op, "
                + "so check the applied field.",
                new String[][] {
                    ARG_PKG,
                    {"PROFILE_ID", "string", "true", "Profile id, as returned by list."},
                },
                new String[][] {
                    {"package", "string"},
                    {"requested_profile_id", "string"},
                    {"active_profile_id", "string or null"},
                    {"applied", "boolean"},
                });
        appendCommand(sb, "delete-profile", true,
                "Delete a profile of PKG. If it was the active one the next remaining profile "
                + "becomes active; deleting the last one unmanages the package.",
                new String[][] {
                    ARG_PKG,
                    {"PROFILE_ID", "string", "true", "Profile id, as returned by list."},
                },
                new String[][] {
                    {"package", "string"},
                    {"profile_id", "string"},
                });
        sb.append("]");
        sb.append("}");
        pw.println(sb);
        return 0;
    }

    /**
     * One entry of the mcp-describe command list.
     *
     * @param arguments rows of {name, type, "true"/"false" required, description}
     * @param returns rows of {field, type}
     */
    private static void appendCommand(StringBuilder sb, String name, boolean mutating,
            String description, String[][] arguments, String[][] returns) {
        nextElement(sb);
        sb.append("{");
        appendStr(sb, "name", name);
        appendRaw(sb, "mutating", Boolean.toString(mutating));
        appendRaw(sb, "requires_adb_control", Boolean.toString(mutating));
        appendStr(sb, "description", description);
        appendKey(sb, "arguments");
        sb.append("[");
        for (String[] a : arguments) {
            nextElement(sb);
            sb.append("{");
            appendStr(sb, "name", a[0]);
            appendStr(sb, "type", a[1]);
            appendRaw(sb, "required", "true".equals(a[2]) ? "true" : "false");
            appendStr(sb, "description", a[3]);
            sb.append("}");
        }
        sb.append("]");
        appendKey(sb, "returns");
        sb.append("[");
        for (String[] r : returns) {
            nextElement(sb);
            sb.append("{");
            appendStr(sb, "field", r[0]);
            appendStr(sb, "type", r[1]);
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("PrivacyKit control (adb shell cmd privacykit ...)");
        pw.println();
        pw.println("Read (always available from adb):");
        pw.println("  status                         Is the ADB-control gate on?");
        pw.println("  list [PKG]                     Managed packages, or PKG's profiles");
        pw.println("  active PKG                     PKG's active profile");
        pw.println("  get-rule PKG KEY               Rule type/value for KEY");
        pw.println("  resolve PKG KEY [REAL]         What PKG would read for KEY");
        pw.println("  dump PKG                       Every set rule + resolved value");
        pw.println("  mcp-describe                   JSON schema of this command surface");
        pw.println();
        pw.println("Mutate (require Settings > PrivacyKit > Settings > Developer > ADB control):");
        pw.println("  set-rule PKG KEY TYPE [VALUE]  TYPE = real|static|per_launch|daily|custom|empty");
        pw.println("  clear-rule PKG KEY");
        pw.println("  create-profile PKG NAME [MODE] MODE = isolated|hybrid|shared");
        pw.println("  set-active PKG PROFILE_ID");
        pw.println("  delete-profile PKG PROFILE_ID");
        pw.println();
        pw.println("Machine-readable output (tooling, AI agents):");
        pw.println("  --json | -j                    Emit JSON instead of prose. Opt-in, and it");
        pw.println("                                 must come BEFORE the subcommand so it is");
        pw.println("                                 never taken for a positional argument.");
        pw.println("  mcp-describe                   JSON description of every subcommand, its");
        pw.println("                                 arguments, its result fields and the");
        pw.println("                                 ADB-control gate. Always JSON.");
        pw.println("  JSON changes rendering only: mutating subcommands still require ADB");
        pw.println("  control, and every subcommand still requires the shell/root uid.");
        pw.println();
        pw.println("Example: cmd privacykit set-rule com.test.gmsprobe android_id static");
        pw.println("         cmd privacykit resolve  com.test.gmsprobe android_id");
        pw.println("         cmd privacykit --json dump com.test.gmsprobe");
        pw.println("         cmd privacykit mcp-describe");
    }
}
