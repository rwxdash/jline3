/*
 * Copyright (c) 2002-2019, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import static org.jline.keymap.KeyMap.range;
import static org.jline.keymap.KeyMap.ctrl;
import static org.jline.keymap.KeyMap.alt;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReader.SuggestionType;
import org.jline.reader.Parser.ParseContext;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.reader.impl.BufferImpl;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

public abstract class Widgets {
    protected static final String AP_TOGGLE = "autopair-toggle";
    protected static final String AP_INSERT = "_autopair-insert";
    protected static final String AP_BACKWARD_DELETE_CHAR = "_autopair-backward-delete-char";
    protected static final String TT_TOGGLE = "tailtip-toggle";
    protected static final String TT_ACCEPT_LINE = "_tailtip-accept-line";

    private final LineReader reader;

    public Widgets(LineReader reader) {
        this.reader = reader;
    }

    public void addWidget(String name, Widget widget) {
        reader.getWidgets().put(name, namedWidget(name, widget));
    }

    private Widget namedWidget(final String name, final Widget widget) {
        return new Widget() {
            @Override
            public String toString() {
                return name;
            }
            @Override
            public boolean apply() {
                return widget.apply();
            }
        };
    }

    public void callWidget(String name) {
        if (!name.startsWith("_") && !name.endsWith("-toggle")) {
            name = "." + name;
        }
        reader.callWidget(name);
    }

    public void executeWidget(String name) {
        // WORK-AROUND
        getKeyMap().bind(new Reference(name), alt(ctrl('X')));
        reader.runMacro(alt(ctrl('X')));
        // The line below should be executed inside readLine()!!!
        // Create LineReader method executeWidget() maybe???
        //
        // widget(name).apply();
    }

    public void aliasWidget(String orig, String alias) {
        reader.getWidgets().put(alias, widget(orig));
    }

    public String getWidget(String name) {
        return widget(name).toString();
    }

    public boolean existsWidget(String name) {
        try {
            widget(name);
            return true;
        } catch(Exception e) {
        }
        return false;
    }

    private Widget widget(String name) {
        Widget out = null;
        if (name.startsWith(".")) {
            out = reader.getBuiltinWidgets().get(name.substring(1));
        } else {
            out = reader.getWidgets().get(name);
        }
        if (out == null) {
            throw new InvalidParameterException("widget: no such widget " + name);
        }
        return out;
    }

    public KeyMap<Binding> getKeyMap() {
        return reader.getKeyMaps().get(LineReader.MAIN);
    }

    public Buffer buffer() {
        return reader.getBuffer();
    }

    public void replaceBuffer(Buffer buffer) {
        reader.getBuffer().copyFrom(buffer);
    }

    public List<String> args(String line) {
        return reader.getParser().parse(line, 0, ParseContext.COMPLETE).words();
    }

    public String prevChar() {
        return String.valueOf((char)reader.getBuffer().prevChar());
    }

    public String currChar() {
        return String.valueOf((char)reader.getBuffer().currChar());
    }

    public String lastBinding() {
        return reader.getLastBinding();
    }

    public void putString(String string) {
        reader.getBuffer().write(string);
    }

    public String tailTip() {
        return reader.getTailTip();
    }

    public void setTailTip(String tailTip) {
        reader.setTailTip(tailTip);
    }

    public void clearTailTip() {
        reader.setTailTip("");
    }

    public void setSuggestionType(SuggestionType type) {
        reader.setAutosuggestion(type);
    }

    public void addDescription(List<AttributedString> desc) {
        Status.getStatus(reader.getTerminal()).update(desc);
    }

    public void clearDescription() {
        initDescription(0);
    }

    public void initDescription(int size) {
        Status status = Status.getStatus(reader.getTerminal(), false);
        if (size > 0) {
            if (status == null) {
                status = Status.getStatus(reader.getTerminal());
            }
            status.setBorder(true);
            List<AttributedString> as = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                as.add(new AttributedString(""));
            }
            addDescription(as);
            reader.runMacro(KeyMap.ctrl('M'));
        } else if (status != null) {
            if (size < 0) {
                status.update(null);
                reader.runMacro(KeyMap.ctrl('M'));
            } else {
                status.clear();
            }
        }
    }

    public void destroyDescription() {
        initDescription(-1);
    }

    /**
     * Creates and manages widgets that auto-closes, deletes and skips over matching delimiters intelligently.
     */
    public static class AutopairWidgets extends Widgets {
        /*
         *  Inspired by zsh-autopair
         *  https://github.com/hlissner/zsh-autopair
         */
        private static final Map<String,String> LBOUNDS;
        private static final Map<String,String> RBOUNDS;
        private final Map<String,String> pairs;
        private final Map<String,Binding> defaultBindings = new HashMap<>();
        private boolean enabled;

        {
            pairs = new HashMap<>();
            pairs.put("`", "`");
            pairs.put("'", "'");
            pairs.put("\"", "\"");
            pairs.put("[", "]");
            pairs.put("(", ")");
            pairs.put(" ", " ");
        }
        static {
            LBOUNDS = new HashMap<>();
            LBOUNDS.put("all", "[.:/\\!]");
            LBOUNDS.put("quotes", "[\\]})a-zA-Z0-9]");
            LBOUNDS.put("spaces", "[^{(\\[]");
            LBOUNDS.put("braces", "");
            LBOUNDS.put("`", "`");
            LBOUNDS.put("\"", "\"");
            LBOUNDS.put("'", "'");
            RBOUNDS = new HashMap<>();
            RBOUNDS.put("all", "[\\[{(<,.:?/%$!a-zA-Z0-9]");
            RBOUNDS.put("quotes", "[a-zA-Z0-9]");
            RBOUNDS.put("spaces", "[^\\]})]");
            RBOUNDS.put("braces", "");
            RBOUNDS.put("`", "");
            RBOUNDS.put("\"", "");
            RBOUNDS.put("'", "");
        }

        public AutopairWidgets(LineReader reader) {
            this(reader, false);
        }

        public AutopairWidgets(LineReader reader, boolean addCurlyBrackets) {
            super(reader);
            if (existsWidget(AP_INSERT)) {
                throw new IllegalStateException("AutopairWidgets already created!");
            }
            if (addCurlyBrackets) {
                pairs.put("{", "}");
            }
            addWidget(AP_INSERT, this::autopairInsert);
            addWidget("_autopair-close", this::autopairClose);
            addWidget(AP_BACKWARD_DELETE_CHAR, this::autopairDelete);
            addWidget(AP_TOGGLE, this::toggleKeyBindings);

            KeyMap<Binding> map = getKeyMap();
            for (Map.Entry<String, String> p: pairs.entrySet()) {
                defaultBindings.put(p.getKey(), map.getBound(p.getKey()));
                if (!p.getKey().equals(p.getValue())) {
                    defaultBindings.put(p.getValue(), map.getBound(p.getValue()));
                }
            }
        }

        public void enable() {
            if (!enabled) {
                executeWidget(AP_TOGGLE);
            }
        }

        public void disable() {
            if (enabled) {
                executeWidget(AP_TOGGLE);
            }
        }

        public boolean toggle() {
            boolean before = enabled;
            executeWidget(AP_TOGGLE);
            return !before;
        }

        /*
         * Widgets
         */
        public boolean autopairInsert() {
            if (pairs.containsKey(lastBinding())) {
                if (canSkip(lastBinding())) {
                    callWidget(LineReader.FORWARD_CHAR);
                } else if (canPair(lastBinding())) {
                    callWidget(LineReader.SELF_INSERT);
                    putString(pairs.get(lastBinding()));
                    callWidget(LineReader.BACKWARD_CHAR);
                } else {
                    callWidget(LineReader.SELF_INSERT);
                }
            } else {
                callWidget(LineReader.SELF_INSERT);
            }
            return true;
        }

        public boolean autopairClose() {
            if (pairs.containsValue(lastBinding())
                && currChar().equals(lastBinding())) {
                callWidget(LineReader.FORWARD_CHAR);
            } else {
                callWidget(LineReader.SELF_INSERT);
            }
            return true;
        }

        public boolean autopairDelete() {
            if (pairs.containsKey(prevChar()) && pairs.get(prevChar()).equals(currChar())
                    && canDelete(prevChar())) {
                callWidget(LineReader.DELETE_CHAR);
            }
            callWidget(LineReader.BACKWARD_DELETE_CHAR);
            return true;
        }

        public boolean toggleKeyBindings() {
            if (enabled) {
                defaultBindings();
            } else {
                customBindings();
            }
            return enabled;
        }
        /*
         * key bindings...
         *
         */
        private void customBindings() {
            boolean ttActive = tailtipEnabled();
            if (ttActive) {
                callWidget(TT_TOGGLE);
            }
            KeyMap<Binding> map = getKeyMap();
            for (Map.Entry<String, String> p: pairs.entrySet()) {
                map.bind(new Reference(AP_INSERT), p.getKey());
                if (!p.getKey().equals(p.getValue())) {
                    map.bind(new Reference("_autopair-close"), p.getValue());
                }
            }
            aliasWidget(AP_BACKWARD_DELETE_CHAR, LineReader.BACKWARD_DELETE_CHAR);
            if (ttActive) {
                callWidget(TT_TOGGLE);
            }
            enabled = true;
        }

        private void defaultBindings() {
            KeyMap<Binding> map = getKeyMap();
            for (Map.Entry<String, String> p: pairs.entrySet()) {
                map.bind(defaultBindings.get(p.getKey()), p.getKey());
                if (!p.getKey().equals(p.getValue())) {
                    map.bind(defaultBindings.get(p.getValue()), p.getValue());
                }
            }
            aliasWidget("." + LineReader.BACKWARD_DELETE_CHAR, LineReader.BACKWARD_DELETE_CHAR);
            if (tailtipEnabled()) {
                callWidget(TT_TOGGLE);
                callWidget(TT_TOGGLE);
            }
            enabled = false;
        }
        /*
         * helpers
         */
        private boolean tailtipEnabled() {
            return getWidget(LineReader.ACCEPT_LINE).equals(TT_ACCEPT_LINE);
        }

        private boolean canPair(String d) {
            if (balanced(d) && !nexToBoundary(d)) {
                if (d.equals(" ") && (prevChar().equals(" ") || currChar().equals(" "))) {
                    return false;
                } else {
                    return true;
                }
            }
            return false;
        }

        private boolean canSkip(String d) {
            if (pairs.get(d).equals(d) && d.charAt(0) != ' ' && currChar().equals(d)
                    && balanced(d)) {
                return true;
            }
            return false;
        }

        private boolean canDelete(String d) {
            if (balanced(d)) {
                return true;
            }
            return false;
        }

        private boolean balanced(String d) {
            boolean out = false;
            Buffer buf = buffer();
            String lbuf = buf.upToCursor();
            String rbuf = buf.substring(lbuf.length());
            String regx1 = pairs.get(d).equals(d)? d : "\\"+d;
            String regx2 = pairs.get(d).equals(d)? pairs.get(d) : "\\"+pairs.get(d);
            int llen = lbuf.length() - lbuf.replaceAll(regx1, "").length();
            int rlen = rbuf.length() - rbuf.replaceAll(regx2, "").length();
            if (llen == 0 && rlen == 0) {
                out = true;
            } else if (d.charAt(0) == ' ') {
                   out = true;
            } else if (pairs.get(d).equals(d)) {
                if ( llen == rlen || (llen + rlen) % 2 == 0 ) {
                    out = true;
                }
            } else {
                int l2len = lbuf.length() - lbuf.replaceAll(regx2, "").length();
                int r2len = rbuf.length() - rbuf.replaceAll(regx1, "").length();
                int ltotal = llen - l2len;
                int rtotal = rlen - r2len;
                if (ltotal < 0) {
                    ltotal = 0;
                }
                if (ltotal >= rtotal) {
                    out = true;
                }
            }
            return out;
        }

        private boolean boundary(String lb, String rb) {
            if ((lb.length() > 0 && prevChar().matches(lb))
                    ||
               (rb.length() > 0 && currChar().matches(rb))) {
                return true;
            }
            return false;
        }

        private boolean nexToBoundary(String d) {
            List<String> bk = new ArrayList<>();
            bk.add("all");
            if (d.matches("['\"`]")) {
                bk.add("quotes");
            } else if (d.matches("[{\\[(<]")) {
                bk.add("braces");
            } else if (d.charAt(0) == ' ') {
                bk.add("spaces");
            }
            if (LBOUNDS.containsKey(d) && RBOUNDS.containsKey(d)) {
                bk.add(d);
            }
            for (String k: bk) {
                if (boundary(LBOUNDS.get(k), RBOUNDS.get(k))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Creates and manages widgets for as you type command line suggestions.
     * Suggestions are created using a using command history.
     */
    public static class AutosuggestionWidgets extends Widgets {
        private boolean enabled = false;

        public AutosuggestionWidgets(LineReader reader) {
            super(reader);
            if (existsWidget("_autosuggest-forward-char")) {
                throw new IllegalStateException("AutosuggestionWidgets already created!");
            }
            addWidget("_autosuggest-forward-char", this::autosuggestForwardChar);
            addWidget("_autosuggest-end-of-line", this::autosuggestEndOfLine);
            addWidget("_autosuggest-forward-word", this::partialAccept);
            addWidget("autosuggest-toggle", this::toggleKeyBindings);
        }

        public void disable() {
            defaultBindings();
        }

        public void enable() {
            customBindings();
        }
        /*
         * Widgets
         */
        public boolean partialAccept() {
            Buffer buffer = buffer();
            if (buffer.cursor() == buffer.length()) {
                int curPos = buffer.cursor();
                buffer.write(tailTip());
                buffer.cursor(curPos);
                replaceBuffer(buffer);
                callWidget(LineReader.FORWARD_WORD);
                Buffer newBuf = new BufferImpl();
                newBuf.write(buffer().substring(0, buffer().cursor()));
                replaceBuffer(newBuf);
            } else {
                callWidget(LineReader.FORWARD_WORD);
            }
            return true;
        }

        public boolean autosuggestForwardChar() {
            return accept(LineReader.FORWARD_CHAR);
        }

        public boolean autosuggestEndOfLine() {
            return accept(LineReader.END_OF_LINE);
        }

        public boolean toggleKeyBindings() {
            if (enabled) {
                defaultBindings();
            } else {
                customBindings();
            }
            return enabled;
        }


        private boolean accept(String widget) {
            Buffer buffer = buffer();
            if (buffer.cursor() == buffer.length()) {
                putString(tailTip());
            } else {
                callWidget(widget);
            }
            return true;
        }
        /*
         * key bindings...
         *
         */
        private void customBindings() {
            if (enabled) {
                return;
            }
            aliasWidget("_autosuggest-forward-char", LineReader.FORWARD_CHAR);
            aliasWidget("_autosuggest-end-of-line", LineReader.END_OF_LINE);
            aliasWidget("_autosuggest-forward-word", LineReader.FORWARD_WORD);
            enabled = true;
            setSuggestionType(SuggestionType.HISTORY);
        }

        private void defaultBindings() {
            if (!enabled) {
                return;
            }
            aliasWidget("." + LineReader.FORWARD_CHAR, LineReader.FORWARD_CHAR);
            aliasWidget("." + LineReader.END_OF_LINE, LineReader.END_OF_LINE);
            aliasWidget("." + LineReader.FORWARD_WORD, LineReader.FORWARD_WORD);
            enabled = false;
            setSuggestionType(SuggestionType.NONE);
        }
    }

    /**
     * Creates and manages widgets for as you type command line suggestions.
     * Suggestions are created using a command completer data and/or positional argument descriptions.
     */
    public static class TailTipWidgets extends Widgets {
        public enum TipType {
            /**
             * Prepare command line suggestions using a command positional argument descriptions.
             */
            TAIL_TIP,
            /**
             * Prepare command line suggestions using a command completer data.
             */
            COMPLETER,
            /**
             * Prepare command line suggestions using either a command positional argument descriptions if exists
             * or command completers data.
             */
            COMBINED
        }
        private boolean enabled = false;
        private CommandDescriptions cmdDescs;
        private TipType tipType;
        private int descriptionSize = 0;
        private boolean descriptionEnabled = true;

        /**
         * Creates tailtip widgets used in command line suggestions. Suggestions are created using a command
         * positional argument names. If argument descriptions do not exists command completer data will be used.
         * Status bar for argument descriptions will not be created.
         *
         * @param reader      LineReader.
         * @param tailTips    Commands options and positional argument descriptions.
         * @throws IllegalStateException     If widgets are already created.
         */
        public TailTipWidgets(LineReader reader, Map<String,CmdDesc> tailTips) {
            this(reader, tailTips, 0, TipType.COMBINED);
        }

        /**
         * Creates tailtip widgets used in command line suggestions.
         * Status bar for argument descriptions will not be created.
         *
         * @param reader      LineReader.
         * @param tailTips    Commands options and positional argument descriptions.
         * @param tipType     Defines which data will be used for suggestions.
         * @throws IllegalStateException     If widgets are already created.
         */
        public TailTipWidgets(LineReader reader, Map<String,CmdDesc> tailTips, TipType tipType) {
            this(reader, tailTips, 0, tipType);
        }

        /**
         * Creates tailtip widgets used in command line suggestions. Suggestions are created using a command
         * positional argument names. If argument descriptions do not exists command completer data will be used.
         *
         * @param reader           LineReader.
         * @param tailTips         Commands options and positional argument descriptions.
         * @param descriptionSize  Size of the status bar.
         * @throws IllegalStateException     If widgets are already created.
         */
        public TailTipWidgets(LineReader reader, Map<String,CmdDesc> tailTips, int descriptionSize) {
            this(reader, tailTips, descriptionSize, TipType.COMBINED);
        }

        /**
         * Creates tailtip widgets used in command line suggestions.
         *
         * @param reader           LineReader.
         * @param tailTips         Commands options and  positional argument descriptions.
         * @param descriptionSize  Size of the status bar.
         * @param tipType          Defines which data will be used for suggestions.
         * @throws IllegalStateException     If widgets are already created.
         */
        public TailTipWidgets(LineReader reader, Map<String,CmdDesc> tailTips, int descriptionSize, TipType tipType) {
            this(reader, tailTips, descriptionSize, tipType, null);
        }

        /**
         * Creates tailtip widgets used in command line suggestions.
         *
         * @param reader           LineReader.
         * @param descFun          Function that returns command description.
         * @param descriptionSize  Size of the status bar.
         * @param tipType          Defines which data will be used for suggestions.
         * @throws IllegalStateException     If widgets are already created.
         */
        public TailTipWidgets(LineReader reader, Function<String,CmdDesc> descFun, int descriptionSize, TipType tipType) {
            this(reader, null, descriptionSize, tipType, descFun);
        }

        private TailTipWidgets(LineReader reader
                             , Map<String,CmdDesc> tailTips
                             , int descriptionSize, TipType tipType, Function<String,CmdDesc> descFun) {
            super(reader);
            if (existsWidget(TT_ACCEPT_LINE)) {
                throw new IllegalStateException("TailTipWidgets already created!");
            }
            this.cmdDescs = tailTips != null ? new CommandDescriptions(tailTips) : new CommandDescriptions(descFun);
            this.descriptionSize = descriptionSize;
            this.tipType = tipType;
            addWidget(TT_ACCEPT_LINE, this::tailtipAcceptLine);
            addWidget("_tailtip-insert", this::tailtipInsert);
            addWidget("_tailtip-backward-delete-char", this::tailtipBackwardDelete);
            addWidget("_tailtip-delete-char", this::tailtipDelete);
            addWidget("_tailtip-expand-or-complete", this::tailtipComplete);
            addWidget("tailtip-window", this::toggleWindow);
            addWidget(TT_TOGGLE, this::toggleKeyBindings);
        }

        public void setDescriptionSize(int descriptionSize) {
            this.descriptionSize = descriptionSize;
            initDescription(descriptionSize);
        }

        public int getDescriptionSize() {
            return descriptionSize;
        }

        public void setTipType(TipType type) {
            this.tipType = type;
            if (tipType == TipType.TAIL_TIP) {
                setSuggestionType(SuggestionType.TAIL_TIP);
            } else {
                setSuggestionType(SuggestionType.COMPLETER);
            }
        }

        public TipType getTipType() {
            return tipType;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void disable() {
            if (enabled) {
                executeWidget(Widgets.TT_TOGGLE);
            }
        }

        public void enable() {
            if (!enabled) {
                toggleKeyBindings();
            }
        }

        /*
         * widgets
         */
        public boolean tailtipComplete() {
            return doTailTip(LineReader.EXPAND_OR_COMPLETE);
        }

        public boolean tailtipAcceptLine() {
            if (tipType != TipType.TAIL_TIP) {
                setSuggestionType(SuggestionType.COMPLETER);
            }
            clearDescription();
            return clearTailTip(LineReader.ACCEPT_LINE);
        }

        public boolean tailtipBackwardDelete() {
            return doTailTip(autopairEnabled() ? AP_BACKWARD_DELETE_CHAR : LineReader.BACKWARD_DELETE_CHAR);
        }

        private boolean clearTailTip(String widget) {
            clearTailTip();
            callWidget(widget);
            return true;
        }

        public boolean tailtipDelete() {
            clearTailTip();
            return doTailTip(LineReader.DELETE_CHAR);
        }

        public boolean tailtipInsert() {
            return doTailTip(autopairEnabled() ? AP_INSERT : LineReader.SELF_INSERT);
        }

        private boolean doTailTip(String widget) {
            Buffer buffer = buffer();
            callWidget(widget);
            if (buffer.length() == buffer.cursor()) {
                List<String> bp = args(buffer.toString());
                int argnum = 0;
                for (String a: bp) {
                    if (!a.startsWith("-")) {
                        argnum++;
                    }
                }
                String lastArg = !prevChar().equals(" ") ? bp.get(bp.size() - 1) : "";
                int bpsize = argnum;
                boolean doTailTip = true;
                if (widget.endsWith(LineReader.BACKWARD_DELETE_CHAR)) {
                    if (!lastArg.startsWith("-")) {
                        bpsize--;
                    }
                    if (prevChar().equals(" ")) {
                        bpsize++;
                    }
                } else if (!prevChar().equals(" ")) {
                    doTailTip = false;
                }
                boolean clearTip = false;
                CmdDesc cmdDesc = bp.size() > 0 ? cmdDescs.getDescription(bp.get(0)) : null;
                if (cmdDesc != null) {
                    if (lastArg.startsWith("-")) {
                        doDescription(cmdDesc.getOptionDescription(lastArg, descriptionSize));
                    }
                    if (bpsize > 0 && doTailTip) {
                        List<ArgDesc> params = cmdDesc.getArgsDesc();
                        setSuggestionType(tipType == TipType.COMPLETER ? SuggestionType.COMPLETER : SuggestionType.TAIL_TIP);
                        if (bpsize - 1 < params.size()) {
                            if (!lastArg.startsWith("-")) {
                                List<AttributedString> d = params.get(bpsize - 1).getDescription();
                                if (d.isEmpty()) {
                                    d = cmdDesc.getMainDescription(descriptionSize);
                                }
                                doDescription(d);
                            }
                            StringBuilder tip = new StringBuilder();
                            for (int i = bpsize - 1; i < params.size(); i++) {
                                tip.append(params.get(i).getName());
                                tip.append(" ");
                            }
                            setTailTip(tip.toString());
                        } else if (params.get(params.size() - 1).getName().charAt(0) == '[') {
                            setTailTip(params.get(params.size() - 1).getName());
                            doDescription(params.get(params.size() - 1).getDescription());
                        }
                    } else if (doTailTip) {
                        clearTip = true;
                    }
                } else {
                    clearDescription();
                    clearTip = true;
                }
                if (clearTip) {
                    setTailTip("");
                    if (tipType != TipType.TAIL_TIP) {
                        setSuggestionType(SuggestionType.COMPLETER);
                    }
                }
            }
            return true;
        }

        private void doDescription(List<AttributedString> desc) {
            if (descriptionSize == 0 || !descriptionEnabled) {
                return;
            }
            if (desc.isEmpty()) {
                clearDescription();
            } else if (desc.size() == descriptionSize) {
                addDescription(desc);
            } else if (desc.size() > descriptionSize) {
                AttributedStringBuilder asb = new AttributedStringBuilder();
                asb.append(desc.get(descriptionSize - 1)).append("...", new AttributedStyle(AttributedStyle.INVERSE));
                List<AttributedString> mod = new ArrayList<>(desc.subList(0, descriptionSize-1));
                mod.add(asb.toAttributedString());
                addDescription(mod);
            } else if (desc.size() < descriptionSize) {
                while (desc.size() != descriptionSize) {
                    desc.add(new AttributedString(""));
                }
                addDescription(desc);
            }
        }

        private boolean autopairEnabled() {
            Binding binding = getKeyMap().getBound("(");
            if (binding instanceof Reference && ((Reference)binding).name().equals(AP_INSERT)) {
                return true;
            }
            return false;
        }

        public boolean toggleWindow() {
            descriptionEnabled = !descriptionEnabled;
            if (descriptionEnabled) {
                initDescription(descriptionSize);
            } else {
                destroyDescription();
            }
            return true;
        }

        public boolean toggleKeyBindings() {
            if (enabled) {
                defaultBindings();
                destroyDescription();
            } else {
                customBindings();
                if (descriptionEnabled) {
                    initDescription(descriptionSize);
                }
            }
            return enabled;
        }

        /*
         * key bindings...
         *
         */
        private boolean defaultBindings() {
            if (!enabled) {
                return false;
            }
            aliasWidget("." + LineReader.ACCEPT_LINE, LineReader.ACCEPT_LINE);
            aliasWidget("." + LineReader.BACKWARD_DELETE_CHAR, LineReader.BACKWARD_DELETE_CHAR);
            aliasWidget("." + LineReader.DELETE_CHAR, LineReader.DELETE_CHAR);
            aliasWidget("." + LineReader.EXPAND_OR_COMPLETE, LineReader.EXPAND_OR_COMPLETE);
            KeyMap<Binding> map = getKeyMap();
            map.bind(new Reference(LineReader.SELF_INSERT), " ");
            map.bind(new Reference(LineReader.SELF_INSERT), "=");
            map.bind(new Reference(LineReader.SELF_INSERT), "-");
            map.bind(new Reference(LineReader.SELF_INSERT), range("A-Z"));
            map.bind(new Reference(LineReader.SELF_INSERT), range("a-z"));

            setSuggestionType(SuggestionType.NONE);
            if (autopairEnabled()) {
                callWidget(AP_TOGGLE);
                callWidget(AP_TOGGLE);
            }
            enabled = false;
            return true;
        }

        private void customBindings() {
            if (enabled) {
                return;
            }
            aliasWidget(TT_ACCEPT_LINE, LineReader.ACCEPT_LINE);
            aliasWidget("_tailtip-backward-delete-char", LineReader.BACKWARD_DELETE_CHAR);
            aliasWidget("_tailtip-delete-char", LineReader.DELETE_CHAR);
            aliasWidget("_tailtip-expand-or-complete", LineReader.EXPAND_OR_COMPLETE);
            KeyMap<Binding> map = getKeyMap();
            map.bind(new Reference("_tailtip-insert"), " ");
            map.bind(new Reference("_tailtip-insert"), "=");
            map.bind(new Reference("_tailtip-insert"), "-");
            map.bind(new Reference("_tailtip-insert"), range("A-Z"));
            map.bind(new Reference("_tailtip-insert"), range("a-z"));
            if (tipType != TipType.TAIL_TIP) {
                setSuggestionType(SuggestionType.COMPLETER);
            } else {
                setSuggestionType(SuggestionType.TAIL_TIP);
            }
            enabled = true;
        }

        private class CommandDescriptions {
            Map<String,CmdDesc> descriptions = new HashMap<>();
            Function<String,CmdDesc> descFun;

            public CommandDescriptions(Map<String,CmdDesc> descriptions) {
                this.descriptions = new HashMap<>(descriptions);
            }

            public CommandDescriptions(Function<String,CmdDesc> descFun) {
                this.descFun = descFun;
            }

            public boolean hasDescription(String command) {
                if (descFun != null && !descriptions.containsKey(command)) {
                    descriptions.put(command, descFun.apply(command));
                }
                return descriptions.containsKey(command);
            }

            public CmdDesc getDescription(String command) {
                return hasDescription(command) ? descriptions.get(command) : null;
            }
        }

    }

    public static class ArgDesc {
        private String name;
        private List<AttributedString> description = new ArrayList<AttributedString>();

        public ArgDesc(String name) {
            this(name, new ArrayList<AttributedString>());
        }

        public ArgDesc(String name, List<AttributedString> description) {
            this.name = name;
            this.description = new ArrayList<>(description);
        }

        public String getName() {
            return name;
        }

        public List<AttributedString> getDescription() {
            return description;
        }

        public static List<ArgDesc> doArgNames(List<String> names) {
            List<ArgDesc> out = new ArrayList<>();
            for (String n: names) {
                out.add(new ArgDesc(n));
            }
            return out;
        }
    }

    public static class CmdDesc {
        private List<AttributedString> mainDesc;
        private List<ArgDesc> argsDesc;
        private TreeMap<String,List<AttributedString>> optsDesc;

        public CmdDesc(List<ArgDesc> argsDesc) {
            this(new ArrayList<>(), argsDesc, new HashMap<>());
        }

        public CmdDesc(List<ArgDesc> argsDesc, Map<String,List<AttributedString>> optsDesc) {
            this(new ArrayList<>(), argsDesc, optsDesc);
        }

        public CmdDesc(List<AttributedString> mainDesc, List<ArgDesc> argsDesc, Map<String,List<AttributedString>> optsDesc) {
            this.argsDesc = new ArrayList<>(argsDesc);
            this.optsDesc = new TreeMap<>(optsDesc);
            if (mainDesc.isEmpty() && optsDesc.containsKey("main")) {
                this.mainDesc = new ArrayList<>(optsDesc.get("main"));
                this.optsDesc.remove("main");
            } else {
                this.mainDesc = new ArrayList<>(mainDesc);
            }
        }

        public List<ArgDesc> getArgsDesc() {
            return argsDesc;
        }

        public List<AttributedString> getMainDescription(int descriptionSize) {
            List<AttributedString> out = new ArrayList<>();
            if (mainDesc.size() <= descriptionSize) {
                out = mainDesc;
            } else {
                int tabs = 0;
                int row = 0;
                for (AttributedString as: mainDesc) {
                    if (as.columnLength() >= tabs) {
                        tabs = as.columnLength() + 2;
                    }
                    row++;
                }
                row = 0;
                List<AttributedString> descList = new ArrayList<>();
                for (int i = 0; i < descriptionSize; i++) {
                    descList.add(new AttributedString(""));
                }
                for (AttributedString as: mainDesc) {
                    AttributedStringBuilder asb = new AttributedStringBuilder().tabs(tabs);
                    asb.append(descList.get(row));
                    asb.append(as);
                    asb.append("\t");
                    descList.remove(row);
                    descList.add(row, asb.toAttributedString());
                    row++;
                    if (row >= descriptionSize) {
                        row = 0;
                    }
                }
                out = new ArrayList<>(descList);
            }
            return out;
        }

        public List<AttributedString> getOptionDescription(String opt, int descriptionSize) {
            List<AttributedString> out = new ArrayList<>();
            if (!opt.startsWith("-")) {
                return out;
            } else if (opt.startsWith("--")) {
                int ind = opt.indexOf("=");
                if (ind > 0) {
                    opt = opt.substring(0, ind);
                }
            }
            List<String> matched = new ArrayList<>();
            int tabs = 0;
            for (String key: optsDesc.keySet()) {
                for (String k: key.split("\\s+")) {
                    if (k.trim().startsWith(opt)) {
                        matched.add(key);
                        if (key.length() >= tabs) {
                            tabs = key.length() + 2;
                        }
                        break;
                    }
                }
            }
            if (matched.size() == 1) {
                out.add(new AttributedString(matched.get(0), new AttributedStyle(AttributedStyle.BOLD)));
                for (AttributedString as: optsDesc.get(matched.get(0))) {
                    AttributedStringBuilder asb = new AttributedStringBuilder().tabs(8);
                    asb.append("\t");
                    asb.append(as);
                    out.add(asb.toAttributedString());
                }
            } else if (matched.size() <= descriptionSize) {
                for (String key: matched) {
                    AttributedStringBuilder asb = new AttributedStringBuilder().tabs(tabs);
                    asb.append(new AttributedString(key, new AttributedStyle(AttributedStyle.BOLD)));
                    asb.append("\t");
                    asb.append(optsDesc.get(key).get(0));
                    out.add(asb.toAttributedString());
                }
            } else if (matched.size() <= 2*descriptionSize) {
                List<AttributedString> keyList = new ArrayList<>();
                int row = 0;
                int columnWidth = 2*tabs;
                while (columnWidth < 50) {
                    columnWidth += tabs;
                }
                for (String key: matched) {
                    AttributedStringBuilder asb = new AttributedStringBuilder().tabs(tabs);
                    if (row < descriptionSize) {
                        asb.append(new AttributedString(key, new AttributedStyle(AttributedStyle.BOLD)));
                        asb.append("\t");
                        asb.append(optsDesc.get(key).get(0));
                        if (asb.columnLength() > columnWidth - 2) {
                            AttributedString trunc = asb.columnSubSequence(0, columnWidth - 5);
                            asb = new AttributedStringBuilder().tabs(tabs);
                            asb.append(trunc);
                            asb.append("...", new AttributedStyle(AttributedStyle.INVERSE));
                            asb.append("  ");
                        } else {
                            for (int i = asb.columnLength(); i < columnWidth; i++) {
                                asb.append(" ");
                            }
                        }
                        keyList.add(asb.toAttributedString().columnSubSequence(0, columnWidth));
                    } else {
                        asb.append(keyList.get(row - descriptionSize));
                        asb.append(new AttributedString(key, new AttributedStyle(AttributedStyle.BOLD)));
                        asb.append("\t");
                        asb.append(optsDesc.get(key).get(0));
                        keyList.remove(row - descriptionSize);
                        keyList.add(row - descriptionSize, asb.toAttributedString());

                    }
                    row++;
                }
                out = new ArrayList<>(keyList);
            } else {
                List<AttributedString> keyList = new ArrayList<>();
                for (int i = 0; i < descriptionSize; i++) {
                    keyList.add(new AttributedString(""));
                }
                int row = 0;
                for (String key: matched) {
                    AttributedStringBuilder asb = new AttributedStringBuilder().tabs(tabs);
                    asb.append(keyList.get(row));
                    asb.append(key);
                    asb.append("\t");
                    keyList.remove(row);
                    keyList.add(row, asb.toAttributedString());
                    row++;
                    if (row >= descriptionSize) {
                        row = 0;
                    }
                }
                out = new ArrayList<>(keyList);
            }
            return out;
        }
    }

}
