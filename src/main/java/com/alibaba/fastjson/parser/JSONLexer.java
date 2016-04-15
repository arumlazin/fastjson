package com.alibaba.fastjson.parser;

import static com.alibaba.fastjson.parser.JSONToken.COLON;
import static com.alibaba.fastjson.parser.JSONToken.COMMA;
import static com.alibaba.fastjson.parser.JSONToken.EOF;
import static com.alibaba.fastjson.parser.JSONToken.ERROR;
import static com.alibaba.fastjson.parser.JSONToken.LBRACE;
import static com.alibaba.fastjson.parser.JSONToken.LBRACKET;
import static com.alibaba.fastjson.parser.JSONToken.LPAREN;
import static com.alibaba.fastjson.parser.JSONToken.RBRACE;
import static com.alibaba.fastjson.parser.JSONToken.RBRACKET;
import static com.alibaba.fastjson.parser.JSONToken.RPAREN;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.util.TypeUtils;

public final class JSONLexer {

    public final static byte EOI            = 0x1A;
    public final static int  NOT_MATCH      = -1;
    public final static int  NOT_MATCH_NAME = -2;
    public final static int  UNKNOWN         = 0;
    public final static int  OBJECT         = 1;
    public final static int  ARRAY          = 2;
    public final static int  VALUE          = 3;
    public final static int  END            = 4;
    
    private final static Map<String, Integer> DEFAULT_KEYWORDS;
    
    private final static boolean SUBSTR = TypeUtils.ANDROID_SDK_VERSION >= 23; // android 6

    static {
        Map<String, Integer> map = new HashMap<String, Integer>();
        map.put("null", JSONToken.NULL);
        map.put("new", JSONToken.NEW);
        map.put("true", JSONToken.TRUE);
        map.put("false", JSONToken.FALSE);
        map.put("undefined", JSONToken.UNDEFINED);
        DEFAULT_KEYWORDS = map;
    }

    protected int                                           token;
    protected int                                           pos;
    public int                                              features       = JSON.DEFAULT_PARSER_FEATURE;

    protected char                                          ch;
    protected int                                           bp;

    protected int                                           eofPos;

    /**
     * A character buffer for literals.
     */
    protected char[]                                        sbuf;
    protected int                                           sp;

    /**
     * number start position
     */
    protected int                                           np;

    protected boolean                                       hasSpecial;

    protected Calendar                                      calendar       = null;

    public int                                              matchStat      = UNKNOWN;

    private final static ThreadLocal<char[]> SBUF_REF_LOCAL = new ThreadLocal<char[]>();
    protected Map<String, Integer>                          keywods        = DEFAULT_KEYWORDS;
    
    protected final String text;
    protected final int len;
    
    public JSONLexer(String input){
        this(input, JSON.DEFAULT_PARSER_FEATURE);
    }
    
    public JSONLexer(char[] input, int inputLength){
        this(input, inputLength, JSON.DEFAULT_PARSER_FEATURE);
    }

    public JSONLexer(char[] input, int inputLength, int features){
        this(new String(input, 0, inputLength), features);
    }

    public JSONLexer(String input, int features){
        sbuf = SBUF_REF_LOCAL.get();

        if (sbuf == null) {
            sbuf = new char[256];
        }
        
        this.features = features;

        text = input;
        len = text.length();
        bp = -1;

        // next();
        {
            int index = ++bp;
            if (index >= len) {
                ch = EOI;
            } else {
                ch = text.charAt(index);
            }
        }
        if (ch == 65279) {
            next();
        }
    }
    
    public final int token() {
        return token;
    }
    
    public void close() {
        if (sbuf.length <= 8196) {
            SBUF_REF_LOCAL.set(sbuf);
        }
        this.sbuf = null;
    }

    public final char getCurrent() {
        return ch;
    }

    public char next() {
        int index = ++bp;
        if (index >= len) {
            return ch = EOI;
        }

        return ch = text.charAt(index);
    }

    public final void config(Feature feature, boolean state) {
        if (state) {
            features |= feature.mask;
        } else {
            features &= ~feature.mask;
        }
    }

    public final boolean isEnabled(Feature feature) {
        return (features & feature.mask) != 0;
    }

    public final void nextTokenWithChar(char expect) {
        sp = 0;

        for (;;) {
            if (ch == expect) {
                // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
                nextToken();
                return;
            }

            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b') {
                next();
                continue;
            }

            throw new JSONException("not match " + expect + " - " + ch);
        }
    }
    
    public final boolean isRef() {
        if (sp != 4) {
            return false;
        }

        return charAt(np + 1) == '$' && charAt(np + 2) == 'r' && charAt(np + 3) == 'e' && charAt(np + 4) == 'f';
    }

    public final String numberString() {
        int index = np + sp - 1;
        char chLocal = text.charAt(index);

        int sp = this.sp;
        if (chLocal == 'L' || chLocal == 'S' || chLocal == 'B' || chLocal == 'F' || chLocal == 'D') {
            sp--;
        }

        // return text.substring(np, np + sp);
        return this.subString(np, sp);
    }

    protected char charAt(int index) {
        if (index >= len) {
            return EOI;
        } else {
            return text.charAt(index);
        }
    }
    
    public final void nextToken() {
        sp = 0;

        for (;;) {
            pos = bp;
            
            if (ch == '"') {
                scanString();
                return;
            }
            
            if (ch >= '0' && ch <= '9') {
                scanNumber();
                return;
            }

            if (ch == ',') {
                next();
                token = COMMA;
                return;
            }

            if (ch == '-') {
                scanNumber();
                return;
            }

            switch (ch) {
                case '\'':
                    if ((features & Feature.AllowSingleQuotes.mask) == 0) {
                        throw new JSONException("Feature.AllowSingleQuotes is false");
                    }
                    scanString();
                    return;
                case ' ':
                case '\t':
                case '\b':
                case '\f':
                case '\n':
                case '\r':
                    next();
                    break;
                case 't': // true
                    scanTrue();
                    return;
                case 'T': // treeSet
                    scanTreeSet();
                    return;
                case 'S': // set
                    scanSet();
                    return;
                case 'f': // false
                    scanFalse();
                    return;
                case 'n': // new,null
                    scanNullOrNew();
                    return;
                case 'u': // undefined
                    scanUndefined();
                    return;
                case '(':
                    next();
                    token = LPAREN;
                    return;
                case ')':
                    next();
                    token = RPAREN;
                    return;
                case '[':
                    // next();
                    {
                        int index = ++bp;
                        if (index >= len) {
                            ch = EOI;
                        } else {
                            ch = text.charAt(index);
                        }
                    }
                    token = LBRACKET;
                    return;
                case ']':
                    next();
                    token = RBRACKET;
                    return;
                case '{':
                    // next();
                    {
                        int index = ++bp;
                        if (index >= len) {
                            ch = EOI;
                        } else {
                            ch = text.charAt(index);
                        }
                    }
                    token = LBRACE;
                    return;
                case '}':
                    // next();
                    {
                        int index = ++bp;
                        if (index >= len) {
                            ch = EOI;
                        } else {
                            ch = text.charAt(index);
                        }
                    }
                    token = RBRACE;
                    return;
                case ':':
                    next();
                    token = COLON;
                    return;
                default:
                    boolean eof = (bp == len || ch == EOI && bp + 1 == len);
                    if (eof) { // JLS
                        if (token == EOF) {
                            throw new JSONException("EOF error");
                        }

                        token = EOF;
                        pos = bp = eofPos;
                    } else {
                        if (ch <= 31 || ch == 127) {
                            next();
                            break;
                        }
                        token = ERROR;
                        next();
                    }

                    return;
            }
        }

    }

    public final void nextToken(int expect) {
        sp = 0;

        for (;;) {
            switch (expect) {
                case JSONToken.LBRACE:
                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        // next();
                        {
                            int index = ++bp;
                            if (index >= len) {
                                ch = EOI;
                            } else {
                                ch = text.charAt(index);
                            }
                        }
                        return;
                    }
                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        // next();
                        {
                            int index = ++bp;
                            if (index >= len) {
                                this.ch = EOI;
                            } else {
                                this.ch = text.charAt(index);
                            }
                        }
                        return;
                    }
                    break;
                case JSONToken.COMMA:
                    if (ch == ',') {
                        token = JSONToken.COMMA;
                        // next();
                        {
                            int index = ++bp;
                            if (index >= len) {
                                ch = EOI;
                            } else {
                                ch = text.charAt(index);
                            }
                        }
                        return;
                    }

                    if (ch == '}') {
                        token = JSONToken.RBRACE;
                        // next();
                        {
                            int index = ++bp;
                            if (index >= len) {
                                ch = EOI;
                            } else {
                                ch = text.charAt(index);
                            }
                        }
                        return;
                    }

                    if (ch == ']') {
                        token = JSONToken.RBRACKET;
                        // next();
                        {
                            int index = ++bp;
                            if (index >= len) {
                                ch = EOI;
                            } else {
                                ch = text.charAt(index);
                            }
                        }
                        return;
                    }

                    if (ch == EOI) {
                        token = JSONToken.EOF;
                        return;
                    }
                    break;
                case JSONToken.LITERAL_INT:
                    if (ch >= '0' && ch <= '9') {
                        pos = bp;
                        scanNumber();
                        return;
                    }

                    if (ch == '"') {
                        pos = bp;
                        scanString();
                        return;
                    }

                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }

                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        next();
                        return;
                    }

                    break;
                case JSONToken.LITERAL_STRING:
                    if (ch == '"') {
                        pos = bp;
                        scanString();
                        return;
                    }

                    if (ch >= '0' && ch <= '9') {
                        pos = bp;
                        scanNumber();
                        return;
                    }

                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }

                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        // next();
                        {
                            int index = ++bp;
                            if (index >= len) {
                                ch = EOI;
                            } else {
                                ch = text.charAt(index);
                            }
                        }
                        return;
                    }
                    break;
                case JSONToken.LBRACKET:
                    if (ch == '[') {
                        token = JSONToken.LBRACKET;
                        next();
                        return;
                    }

                    if (ch == '{') {
                        token = JSONToken.LBRACE;
                        next();
                        return;
                    }
                    break;
                case JSONToken.RBRACKET:
                    if (ch == ']') {
                        token = JSONToken.RBRACKET;
                        next();
                        return;
                    }
                case JSONToken.EOF:
                    if (ch == EOI) {
                        token = JSONToken.EOF;
                        return;
                    }
                    break;
                case JSONToken.IDENTIFIER:
                    nextIdent();
                    return;
                default:
                    break;
            }

            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b') {
                next();
                continue;
            }

            nextToken();
            break;
        }
    }

    public final void nextIdent() {
        for (;;) {
            boolean whitespace = ch <= ' ' && (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b');
            if (!whitespace) {
                break;
            }
            next();
        }
        
        if (ch == '_' || Character.isLetter(ch)) {
            scanIdent();
        } else {
            nextToken();
        }
    }

    public final String tokenName() {
        return JSONToken.name(token);
    }

    public final int pos() {
        return pos;
    }

    private String stringDefaultValue() {
        if ((features & Feature.InitStringFieldAsEmpty.mask) != 0) {
            return "";
        }
        return null;
    }

    public final Number integerValue() throws NumberFormatException {
        long result = 0;
        boolean negative = false;
        if (np == -1) {
            np = 0;
        }
        int i = np, max = np + sp;
        long limit;
        long multmin;
        int digit;

        char type = ' ';

        switch (charAt(max - 1)) {
            case 'L':
                max--;
                type = 'L';
                break;
            case 'S':
                max--;
                type = 'S';
                break;
            case 'B':
                max--;
                type = 'B';
                break;
            default:
                break;
        }

        if (charAt(np) == '-') {
            negative = true;
            limit = Long.MIN_VALUE;
            i++;
        } else {
            limit = -Long.MAX_VALUE;
        }
        multmin = negative ? MULTMIN_RADIX_TEN : N_MULTMAX_RADIX_TEN;
        if (i < max) {
            digit = digits[charAt(i++)];
            result = -digit;
        }
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            digit = digits[charAt(i++)];
            if (result < multmin) {
                return new BigInteger(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                return new BigInteger(numberString());
            }
            result -= digit;
        }

        if (negative) {
            if (i > np + 1) {
                if (result >= Integer.MIN_VALUE && type != 'L') {
                    if (type == 'S') {
                        return (short) result;
                    }

                    if (type == 'B') {
                        return (byte) result;
                    }

                    return (int) result;
                }
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            result = -result;
            if (result <= Integer.MAX_VALUE && type != 'L') {
                if (type == 'S') {
                    return (short) result;
                }

                if (type == 'B') {
                    return (byte) result;
                }

                return (int) result;
            }
            return result;
        }
    }

    public final String scanSymbol(final SymbolTable symbolTable) {
        for (;;) {
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b') {
                next();
            } else {
                break;
            }
        }

        if (ch == '"') {
            return scanSymbol(symbolTable, '"');
        }

        if (ch == '\'') {
            if ((features & Feature.AllowSingleQuotes.mask) == 0) {
                throw new JSONException("syntax error");
            }

            return scanSymbol(symbolTable, '\'');
        }

        if (ch == '}') {
            next();
            token = JSONToken.RBRACE;
            return null;
        }

        if (ch == ',') {
            next();
            token = JSONToken.COMMA;
            return null;
        }

        if (ch == EOI) {
            token = JSONToken.EOF;
            return null;
        }

        if ((features & Feature.AllowUnQuotedFieldNames.mask) == 0) {
            throw new JSONException("syntax error");
        }

        return scanSymbolUnQuoted(symbolTable);
    }
    
    public String scanSymbol(final SymbolTable symbolTable, char quoteChar) {
        int hash = 0;
        
        boolean hasSpecial = false;
        int startIndex = bp + 1;
        int endIndex = text.indexOf(quoteChar, startIndex);
        if (endIndex == -1) {
            throw new JSONException("unclosed str");
        }
        
        if (SUBSTR && endIndex - startIndex > 5) {
            String strVal = text.substring(startIndex, endIndex);
            if (strVal.indexOf('\\') == -1) {
                bp = endIndex + 1;
                // ch = charAt(bp);
                {
                    int index = bp;
                    if (index >= this.len) {
                        ch = EOI;
                    } else {
                        ch = text.charAt(index);
                    }
                }
                return strVal;
            }
        }

        int chars_len;
        char[] chars;

        chars_len = endIndex - startIndex;
        chars = sub_chars(bp + 1, chars_len);
        while ((chars_len > 0 // 
                && chars[chars_len - 1] == '\\')
                ) {
            
            if (chars_len > 1 && chars[chars_len - 2] == '\\') {
                break;
            }
            
            int nextIndex = text.indexOf(quoteChar, endIndex + 1);
            int nextLen = nextIndex - endIndex;
            int next_chars_len = chars_len + nextLen;
            
            if (next_chars_len < chars.length) {
                text.getChars(endIndex, nextIndex, chars, chars_len);
            } else {
                chars = sub_chars(bp + 1, next_chars_len);
            }
            chars_len = next_chars_len;
            endIndex = nextIndex;
            hasSpecial = true;
        }
        
        final String strVal;
        if (!hasSpecial) {
            for (int i = 0; i < chars_len; ++i) {
                char ch = chars[i];
                hash = 31 * hash + ch;
                if (ch == '\\') {
                    hasSpecial = true;
                }
            }
            
            if (hasSpecial) {
                strVal = toString(chars, chars_len);
            } else if (chars_len < 20) {
                strVal = symbolTable.addSymbol(chars, 0, chars_len, hash);
            } else {
                strVal = new String(chars, 0, chars_len);
            }
        } else {
            strVal = toString(chars, chars_len);
        }
        
        bp = endIndex + 1;
        // ch = charAt(bp);
        {
            int index = bp;
            if (index >= len) {
                ch = EOI;
            } else {
                ch = text.charAt(index);
            }
        }

        return strVal;
    }
    
    private static String toString(char[] chars, int chars_len) {
        char[] sbuf = new char[chars_len];
        int len = 0;
        for (int i = 0; i < chars_len; ++i) {
            char chLocal = chars[i];
            
            if (chLocal != '\\') {
                sbuf[len++] = chLocal;
                continue;
            }
            chLocal = chars[++i];

            switch (chLocal) {
                case '0':
                    sbuf[len++] = '\0';
                    break;
                case '1':
                    sbuf[len++] = '\1';
                    break;
                case '2':
                    sbuf[len++] = '\2';
                    break;
                case '3':
                    sbuf[len++] = '\3';
                    break;
                case '4':
                    sbuf[len++] = '\4';
                    break;
                case '5':
                    sbuf[len++] = '\5';
                    break;
                case '6':
                    sbuf[len++] = '\6';
                    break;
                case '7':
                    sbuf[len++] = '\7';
                    break;
                case 'b': // 8
                    sbuf[len++] = '\b';
                    break;
                case 't': // 9
                    sbuf[len++] = '\t';
                    break;
                case 'n': // 10
                    sbuf[len++] = '\n';
                    break;
                case 'v': // 11
                    sbuf[len++] = '\u000B';
                    break;
                case 'f': // 12
                case 'F':
                    sbuf[len++] = '\f';
                    break;
                case 'r': // 13
                    sbuf[len++] = '\r';
                    break;
                case '"': // 34
                    sbuf[len++] = '"';
                    break;
                case '\'': // 39
                    sbuf[len++] = '\'';
                    break;
                case '/': // 47
                    sbuf[len++] = '/';
                    break;
                case '\\': // 92
                    sbuf[len++] = '\\';
                    break;
                case 'x':
                    char x1 = chars[++i];
                    char x2 = chars[++i];

                    int x_val = digits[x1] * 16 + digits[x2];
                    char x_char = (char) x_val;
                    sbuf[len++] = x_char;
                    break;
                case 'u':
                    char c1 = chars[++i];
                    char c2 = chars[++i];
                    char c3 = chars[++i];
                    char c4 = chars[++i];
                    int val = Integer.parseInt(new String(new char[] { c1, c2, c3, c4 }), 16);
                    sbuf[len++] = (char) val;
                    break;
                default:
                    throw new JSONException("unclosed.str.lit");
            }
        }
        return new String(sbuf, 0, len);
    }
    
    public final void resetStringPosition() {
        this.sp = 0;
    }
    
    public String info() {
        return "pos " + bp //
               + ", json : " //
               + (text.length() < 65536 //
                   ? text //
                   : text.substring(0, 65536));
    }

    public final String scanSymbolUnQuoted(final SymbolTable symbolTable) {
        final char first = ch;

        final boolean firstFlag = ch >= firstIdentifierFlags.length || firstIdentifierFlags[first];
        if (!firstFlag) {
            throw new JSONException("illegal identifier : " + ch //
                                    + ", " + info());
        }

        int hash = first;

        np = bp;
        sp = 1;
        char chLocal;
        for (;;) {
            chLocal = next();

            if (chLocal < identifierFlags.length) {
                if (!identifierFlags[chLocal]) {
                    break;
                }
            }

            hash = 31 * hash + chLocal;

            sp++;
            continue;
        }

        this.ch = charAt(bp);
        token = JSONToken.IDENTIFIER;

        final int NULL_HASH = 3392903;
        if (sp == 4 && hash == NULL_HASH && charAt(np) == 'n' && charAt(np + 1) == 'u' && charAt(np + 2) == 'l'
            && charAt(np + 3) == 'l') {
            return null;
        }

        return symbolTable.addSymbol(text, np, sp, hash);
    }

    public final void scanString() {
        final char quoteChar = ch;
        boolean hasSpecial = false;
        int startIndex = bp + 1;
        int endIndex = text.indexOf(quoteChar, startIndex);
        if (endIndex == -1) {
            throw new JSONException("unclosed str");
        }

        int chars_len;
        char[] chars;

        chars_len = endIndex - startIndex;
        chars = sub_chars(bp + 1, chars_len);
        while ((chars_len > 0 // 
                && chars[chars_len - 1] == '\\')
                ) {
            
            if (chars_len > 1 && chars[chars_len - 2] == '\\') {
                break;
            }
            
            int nextIndex = text.indexOf(quoteChar, endIndex + 1);
            int nextLen = nextIndex - endIndex;
            int next_chars_len = chars_len + nextLen;
            
            if (next_chars_len < chars.length) {
                text.getChars(endIndex, nextIndex, chars, chars_len);
            } else {
                chars = sub_chars(bp + 1, next_chars_len);
            }
            chars_len = next_chars_len;
            endIndex = nextIndex;
            hasSpecial = true;
        }
        
        if (!hasSpecial) {
            for (int i = 0; i < chars_len; ++i) {
                if (chars[i] == '\\') {
                    hasSpecial = true;
                }
            }
        }
        
        sbuf = chars;
        sp = chars_len;
        np = bp;
        this.hasSpecial = hasSpecial;
        
        bp = endIndex + 1;
        // ch = charAt(bp);
        {
            int index = bp;
            if (index >= len) {
                ch = EOI;
            } else {
                ch = text.charAt(index);
            }
        }

        token = JSONToken.LITERAL_STRING;
    }
    
    public String scanStringValue(char quoteChar) {
        boolean hasSpecial = false;
        int startIndex = bp + 1;
        int endIndex = text.indexOf(quoteChar, startIndex);
        if (endIndex == -1) {
            throw new JSONException("unclosed str");
        }
        
        if (SUBSTR) {
            String strVal = text.substring(startIndex, endIndex);
            if (strVal.indexOf('\\') == -1) {
                bp = endIndex + 1;
                // ch = charAt(bp);
                {
                    int index = bp;
                    if (index >= this.len) {
                        ch = EOI;
                    } else {
                        ch = text.charAt(index);
                    }
                }
                return strVal;
            } else {
                hasSpecial = true;
            }
        }

        int chars_len;
        char[] chars;

        chars_len = endIndex - startIndex;
        chars = sub_chars(bp + 1, chars_len);
        while ((chars_len > 0 // 
                && chars[chars_len - 1] == '\\')
                ) {
            
            if (chars_len > 1 && chars[chars_len - 2] == '\\') {
                break;
            }
            
            int nextIndex = text.indexOf(quoteChar, endIndex + 1);
            int nextLen = nextIndex - endIndex;
            int next_chars_len = chars_len + nextLen;
            
            if (next_chars_len < chars.length) {
                text.getChars(endIndex, nextIndex, chars, chars_len);
            } else {
                chars = sub_chars(bp + 1, next_chars_len);
            }
            chars_len = next_chars_len;
            endIndex = nextIndex;
            hasSpecial = true;
        }
        
        final String strVal;
        if (!hasSpecial) {
            for (int i = 0; i < chars_len; ++i) {
                if (chars[i] == '\\') {
                    hasSpecial = true;
                }
            }
            
            if (hasSpecial) {
                strVal = toString(chars, chars_len);
            } else {
                strVal = new String(chars, 0, chars_len);
            }
        } else {
            strVal = toString(chars, chars_len);
        }
        
        bp = endIndex + 1;
        // ch = charAt(bp);
        {
            int index = bp;
            if (index >= len) {
                ch = EOI;
            } else {
                ch = text.charAt(index);
            }
        }
        
        return strVal;
    }

    public Calendar getCalendar() {
        return this.calendar;
    }

    public final int intValue() {
        if (np == -1) {
            np = 0;
        }

        int result = 0;
        boolean negative = false;
        int i = np, max = np + sp;
        int limit;
        int multmin;
        int digit;

        if (charAt(np) == '-') {
            negative = true;
            limit = Integer.MIN_VALUE;
            i++;
        } else {
            limit = -Integer.MAX_VALUE;
        }
        multmin = negative ? INT_MULTMIN_RADIX_TEN : INT_N_MULTMAX_RADIX_TEN;
        if (i < max) {
            digit = digits[charAt(i++)];
            result = -digit;
        }
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            char chLocal = charAt(i++);

            if (chLocal == 'L' || chLocal == 'S' || chLocal == 'B') {
                break;
            }

            digit = digits[chLocal];

            if (result < multmin) {
                throw new NumberFormatException(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException(numberString());
            }
            result -= digit;
        }

        if (negative) {
            if (i > np + 1) {
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            return -result;
        }
    }

    public byte[] bytesValue() {
        return decodeFast(text, np + 1, sp);
    }

    public String scanString(char expectNextChar) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        if (chLocal == 'n') {
            if (charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
            } else {
                matchStat = NOT_MATCH;
                return null;
            }

            if (chLocal == expectNextChar) {
                bp += (offset - 1);
                this.next();
                matchStat = VALUE;
                return null;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        if (chLocal != '"') {
            matchStat = NOT_MATCH;

            return stringDefaultValue();
        }

        boolean hasSpecial = false;
        final String strVal;
        {
            int startIndex = bp + 1;
            int endIndex = text.indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            String stringVal = subString(bp + 1, endIndex - startIndex);
            for (int i = bp + 1; i < endIndex; ++i) {
                if (charAt(i) == '\\') {
                    hasSpecial = true;
                    break;
                }
            }

            if (hasSpecial) {
                matchStat = NOT_MATCH;

                return stringDefaultValue();
            }

            offset += (endIndex - (bp + 1) + 1);
            chLocal = charAt(bp + (offset++));
            strVal = stringVal;
        }

        if (chLocal == expectNextChar) {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            return strVal;
        } else {
            matchStat = NOT_MATCH;
            return strVal;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Enum<?> scanEnum(Class<?> enumClass, final SymbolTable symbolTable, char serperator) {
        String name = scanSymbolWithSeperator(symbolTable, serperator);
        if (name == null) {
            return null;
        }
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }

    public String scanSymbolWithSeperator(final SymbolTable symbolTable, char serperator) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        if (chLocal == 'n') {
            if (charAt(bp + offset) == 'u' && charAt(bp + offset + 1) == 'l' && charAt(bp + offset + 2) == 'l') {
                offset += 3;
                chLocal = charAt(bp + (offset++));
            } else {
                matchStat = NOT_MATCH;
                return null;
            }

            if (chLocal == serperator) {
                bp += (offset - 1);
                this.next();
                matchStat = VALUE;
                return null;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        if (chLocal != '"') {
            matchStat = NOT_MATCH;
            return null;
        }

        String strVal;
        // int start = index;
        int hash = 0;
        for (;;) {
            chLocal = charAt(bp + (offset++));
            if (chLocal == '\"') {
                // bp = index;
                // this.ch = chLocal = charAt(bp);
                int start = bp + 0 + 1;
                int len = bp + offset - start - 1;
                
                strVal = symbolTable.addSymbol(text, start, len, hash);
                chLocal = charAt(bp + (offset++));
                break;
            }

            hash = 31 * hash + chLocal;

            if (chLocal == '\\') {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        if (chLocal == serperator) {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            return strVal;
        } else {
            matchStat = NOT_MATCH;
            return strVal;
        }
    }

    public int scanInt(char expectNext) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        int value;
        if (chLocal >= '0' && chLocal <= '9') {
            value = digits[chLocal];
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + digits[chLocal];
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    break;
                }
            }
            if (value < 0) {
                matchStat = NOT_MATCH;
                return 0;
            }
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == expectNext) {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        } else {
            matchStat = NOT_MATCH;
            return value;
        }
    }

    public long scanLong(char expectNextChar) {
        matchStat = UNKNOWN;

        int offset = 0;
        char chLocal = charAt(bp + (offset++));

        long value;
        if (chLocal >= '0' && chLocal <= '9') {
            value = digits[chLocal];
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + digits[chLocal];
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else {
                    break;
                }
            }
            if (value < 0) {
                matchStat = NOT_MATCH;
                return 0;
            }
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == expectNextChar) {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        } else {
            matchStat = NOT_MATCH;
            return value;
        }
    }

    private void scanTrue() {
        if (ch != 't') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'r') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'u') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
            || ch == '\f' || ch == '\b' || ch == ':') {
            token = JSONToken.TRUE;
        } else {
            throw new JSONException("scan true error");
        }
    }

    private void scanTreeSet() {
        if (ch != 'T') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch != 'r') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch != 'S') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch != 't') {
            throw new JSONException("error parse treeSet");
        }
        next();

        if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b' || ch == '[' || ch == '(') {
            token = JSONToken.TREE_SET;
        } else {
            throw new JSONException("scan treeSet error");
        }
    }

    private void scanNullOrNew() {
        if (ch != 'n') {
            throw new JSONException("error parse null or new");
        }
        next();

        if (ch == 'u') {
            next();
            if (ch != 'l') {
                throw new JSONException("error parse null");
            }
            next();

            if (ch != 'l') {
                throw new JSONException("error parse null");
            }
            next();

            if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
                || ch == '\f' || ch == '\b') {
                token = JSONToken.NULL;
            } else {
                throw new JSONException("scan null error");
            }
            return;
        }

        if (ch != 'e') {
            throw new JSONException("error parse new");
        }
        next();

        if (ch != 'w') {
            throw new JSONException("error parse new");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
            || ch == '\f' || ch == '\b') {
            token = JSONToken.NEW;
        } else {
            throw new JSONException("scan new error");
        }
    }

    private void scanUndefined() {
        if (ch != 'u') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'n') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'd') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'f') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'i') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'n') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse undefined");
        }
        next();
        if (ch != 'd') {
            throw new JSONException("error parse undefined");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
            || ch == '\f' || ch == '\b') {
            token = JSONToken.UNDEFINED;
        } else {
            throw new JSONException("scan undefined error");
        }
    }

    private void scanFalse() {
        if (ch != 'f') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 'a') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 'l') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 's') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse false");
        }
        next();

        if (ch == ' ' || ch == ',' || ch == '}' || ch == ']' || ch == '\n' || ch == '\r' || ch == '\t' || ch == EOI
            || ch == '\f' || ch == '\b' || ch == ':') {
            token = JSONToken.FALSE;
        } else {
            throw new JSONException("scan false error");
        }
    }

    private void scanIdent() {
        np = bp - 1;
        hasSpecial = false;

        for (;;) {
            sp++;

            next();
            if (Character.isLetterOrDigit(ch)) {
                continue;
            }

            String ident = stringVal();
            
            Integer tok = keywods.get(ident);
            if (tok != null) {
                token = tok;
            } else {
                token = JSONToken.IDENTIFIER;
            }
            return;
        }
    }

    public final String stringVal() {
        if (!hasSpecial) {
            // return text.substring(np + 1, np + 1 + sp);
            return this.subString(np + 1, sp);
        } else {
            String strVal = toString(sbuf, sp);
            return strVal;
        }
    }

    public final String subString(int offset, int count) {
        if (count < sbuf.length) {
            text.getChars(offset, offset + count, sbuf, 0);
            return new String(sbuf, 0, count);
        } else {
            char[] chars = new char[count];
            text.getChars(offset, offset + count, chars, 0);
            return new String(chars);
        }
    }
    
    final char[] sub_chars(int offset, int count) {
        if (count < sbuf.length) {
            text.getChars(offset, offset + count, sbuf, 0);
            return sbuf;
        } else {
            char[] chars = new char[count];
            text.getChars(offset, offset + count, chars, 0);
            return chars;
        }
    }

    public final boolean isBlankInput() {
        for (int i = 0;; ++i) {
            char ch = charAt(i);
            if (ch == EOI) {
                break;
            }

            boolean whitespace = ch <= ' ' && (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b');
            if (!whitespace) {
                return false;
            }
        }

        return true;
    }

    public final void skipWhitespace() {
        for (;;) {
            if (ch <= ' ' && (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b')) {
                next();
            } else {
                break;
            }
        }
    }

    public final void scanSet() {
        if (ch != 'S') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 'e') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch != 't') {
            throw new JSONException("error parse true");
        }
        next();

        if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b' || ch == '[' || ch == '(') {
            token = JSONToken.SET;
        } else {
            throw new JSONException("scan set error");
        }
    }

    /**
     * Append a character to sbuf.
     */
    private void putChar(char ch) {
        if (sp == sbuf.length) {
            char[] newsbuf = new char[sbuf.length * 2];
            System.arraycopy(sbuf, 0, newsbuf, 0, sbuf.length);
            sbuf = newsbuf;
        }
        sbuf[sp++] = ch;
    }

    public final void scanNumber() {
        np = bp;

        if (ch == '-') {
            sp++;
            // next();
            {
                int index = ++this.bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
        }

        for (;;) {
            if (ch >= '0' && ch <= '9') {
                sp++;
            } else {
                break;
            }
            // next();
            {
                int index = ++bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = text.charAt(index);
                }
            }
        }

        boolean isDouble = false;

        if (ch == '.') {
            sp++;
            // next();
            {
                int index = ++this.bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
            isDouble = true;

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    sp++;
                } else {
                    break;
                }
                // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            }
        }

        if (ch == 'L') {
            sp++;
            next();
        } else if (ch == 'S') {
            sp++;
            next();
        } else if (ch == 'B') {
            sp++;
            next();
        } else if (ch == 'F') {
            sp++;
            next();
            isDouble = true;
        } else if (ch == 'D') {
            sp++;
            next();
            isDouble = true;
        } else if (ch == 'e' || ch == 'E') {
            sp++;
            // next();
            {
                int index = ++this.bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }

            if (ch == '+' || ch == '-') {
                sp++;
                 // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            }

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    sp++;
                } else {
                    break;
                }
                // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            }

            if (ch == 'D' || ch == 'F') {
                sp++;
                next();
            }

            isDouble = true;
        }

        if (isDouble) {
            token = JSONToken.LITERAL_FLOAT;
        } else {
            token = JSONToken.LITERAL_INT;
        }
    }
    
    public final Number scanNumberValue() {
        final int start = bp;
        
        boolean overflow = false;
        Number number = null;
        np = 0;
        final boolean negative;
        
        final long limit, multmin;
        if (ch == '-') {
            negative = true;
            limit = Long.MIN_VALUE;
            multmin = MULTMIN_RADIX_TEN;
            
            np++;
            // next();
            {
                int index = ++this.bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
        } else {
            negative = false;
            limit = -Long.MAX_VALUE;
            multmin = N_MULTMAX_RADIX_TEN;
        }

        long longValue = 0;
        for (;;) {
            if (ch >= '0' && ch <= '9') {
                int digit = (ch - '0');
                if (longValue < multmin) {
                    overflow = true;
                }
                
                longValue *= 10;
                if (longValue < limit + digit) {
                    overflow = true;
                }
                longValue -= digit;
            } else {
                break;
            }
            
            np++;
            // next();
            {
                int index = ++bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = text.charAt(index);
                }
            }
        }
        
        if (!negative) {
            longValue = -longValue;
        }

        if (ch == 'L') {
            np++;
            next();
            number = longValue;
        } else if (ch == 'S') {
            np++;
            next();
            number = (short) longValue;
        } else if (ch == 'B') {
            np++;
            next();
            number = (byte) longValue;
        } else if (ch == 'F') {
            np++;
            next();
            number = (float) longValue;
        } else if (ch == 'D') {
            np++;
            next();
            number = (double) longValue;
        }
        
        boolean isDouble = false, exp = false;
        if (ch == '.') {
            isDouble = true;
            
            np++;
            // next();
            {
                int index = ++this.bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    np++;
                } else {
                    break;
                }
                // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            }
        }
        
        if (ch == 'e' || ch == 'E') {
            np++;
            // next();
            {
                int index = ++this.bp;
                if (index >= len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }

            if (ch == '+' || ch == '-') {
                np++;
                 // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            }

            for (;;) {
                if (ch >= '0' && ch <= '9') {
                    np++;
                } else {
                    break;
                }
                // next();
                {
                    int index = ++this.bp;
                    if (index >= len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            }

            if (ch == 'D' || ch == 'F') {
                bp++;
                next();
            }

            exp = true;
        }
        
        if ((!isDouble) && (!exp)) {
            if (overflow) {
                int len = bp - start;
                char[] chars = new char[len];
                text.getChars(start, bp, chars, 0);
                String strVal = new String(chars);
                number = new BigInteger(strVal);
            }
            if (number == null) {
                if (longValue > Integer.MIN_VALUE && longValue < Integer.MAX_VALUE) {
                    number = (int) longValue;
                } else {
                    number = longValue;
                }
            }
            return number;
        }
        
        int len = bp - start;
        char[] chars = new char[len];
        text.getChars(start, bp, chars, 0);

        if ((!exp) && (features & Feature.UseBigDecimal.mask) != 0) {
            number = new BigDecimal(chars);
        } else {
            String strVal = new String(chars);
            number = Double.parseDouble(strVal);
        }
        
        return number;
    }

    public final long longValue() throws NumberFormatException {
        long result = 0;
        boolean negative = false;
        int i = np, max = np + sp;
        
        int digit;

        final long limit;
        if (charAt(np) == '-') {
            negative = true;
            limit = Long.MIN_VALUE;
            i++;
        } else {
            limit = -Long.MAX_VALUE;
        }
        final long multmin = negative ? MULTMIN_RADIX_TEN : N_MULTMAX_RADIX_TEN;
        if (i < max) {
            digit = digits[charAt(i++)];
            result = -digit;
        }
        while (i < max) {
            // Accumulating negatively avoids surprises near MAX_VALUE
            // char chLocal = charAt(i++);
            char chLocal;
            {
                int index = i++;
                if (index >= len) {
                    chLocal = EOI;
                } else {
                    chLocal = text.charAt(index);
                }
            }

            if (chLocal == 'L' || chLocal == 'S' || chLocal == 'B') {
                break;
            }

            digit = digits[chLocal];
            if (result < multmin) {
                throw new NumberFormatException(numberString());
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException(numberString());
            }
            result -= digit;
        }

        if (negative) {
            if (i > np + 1) {
                return result;
            } else { /* Only got "-" */
                throw new NumberFormatException(numberString());
            }
        } else {
            return -result;
        }
    }

    public final Number decimalValue(boolean decimal) {
        char chLocal = charAt(np + sp - 1);
        if (chLocal == 'F') {
            return Float.parseFloat(numberString());
            // return Float.parseFloat(new String(buf, np, sp - 1));
        }

        if (chLocal == 'D') {
            return Double.parseDouble(numberString());
            // return Double.parseDouble(new String(buf, np, sp - 1));
        }

        if (decimal) {
            return decimalValue();
        } else {
            return Double.parseDouble(numberString());
        }
    }

    public final BigDecimal decimalValue() {
        return new BigDecimal(numberString());
    }

    public static final boolean isWhitespace(char ch) {
        // 专门调整了判断顺序
        return ch <= ' ' && (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\f' || ch == '\b');
    }

    protected static final long  MULTMIN_RADIX_TEN       = Long.MIN_VALUE / 10;
    protected static final long  N_MULTMAX_RADIX_TEN     = -Long.MAX_VALUE / 10;

    protected static final int   INT_MULTMIN_RADIX_TEN   = Integer.MIN_VALUE / 10;
    protected static final int   INT_N_MULTMAX_RADIX_TEN = -Integer.MAX_VALUE / 10;

    protected final static int[] digits                  = new int[(int) 'f' + 1];

    static {
        for (int i = '0'; i <= '9'; ++i) {
            digits[i] = i - '0';
        }

        for (int i = 'a'; i <= 'f'; ++i) {
            digits[i] = (i - 'a') + 10;
        }
        for (int i = 'A'; i <= 'F'; ++i) {
            digits[i] = (i - 'A') + 10;
        }
    }

    
    public boolean matchField(char[] fieldName) {
        if (!charArrayCompare(fieldName)) {
            return false;
        }

        bp = bp + fieldName.length;
        // ch = charAt(bp);
        {
            if (bp >= len) {
                throw new JSONException("unclosed str");
            } else {
                ch = text.charAt(bp);
            }
        }

        if (ch == '{') {
            {
                int index = ++bp;
                if (index >= len) {
                    ch = EOI;
                } else {
                    ch = text.charAt(index);
                }
            }
            token = JSONToken.LBRACE;
        } else if (ch == '[') {
            {
                int index = ++bp;
                if (index >= len) {
                    ch = EOI;
                } else {
                    ch = text.charAt(index);
                }
            }
            token = JSONToken.LBRACKET;
        } else {
            nextToken();
        }

        return true;
    }
    
    private boolean charArrayCompare(char[] chars) {
        final int destLen = chars.length;
        if (destLen + bp > len) {
            return false;
        }

        for (int i = 0; i < destLen; ++i) {
            if (chars[i] != text.charAt(bp + i)) {
                return false;
            }
        }

        return true;
    }
    
    public int scanFieldInt(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));
        
        boolean quote = false;
        if (chLocal == '"') {
            quote = true;
            
            {
                int index = bp + (offset++);
                if (index >= len) {
                    chLocal = EOI;
                } else {
                    chLocal = text.charAt(index);
                }
            }
        }

        int value;
        if (chLocal >= '0' && chLocal <= '9') {
            value = digits[chLocal];
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + digits[chLocal];
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else if (chLocal == '\"') {
                    if (!quote) {
                        matchStat = NOT_MATCH;
                        return 0;
                    }
                    int index = bp + (offset++);
                    if (index >= len) {
                        chLocal = EOI;
                    } else {
                        chLocal = text.charAt(index);
                    }
                    break;
                } else {
                    break;
                }
            }
            if (value < 0) {
                matchStat = NOT_MATCH;
                return 0;
            }
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            // this.next();
            {
                int index = ++bp;
                if (index >= this.len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return value;
    }
    
    public long scanFieldLong(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        // char chLocal = charAt(bp + (offset++));
        char chLocal;
        {
            int index = bp + (offset++);
            if (index >= len) {
                chLocal = EOI;
            } else {
                chLocal = text.charAt(index);
            }
        }

        long value;
        
        boolean quote = false;
        if (chLocal == '"') {
            quote = true;
            
            {
                int index = bp + (offset++);
                if (index >= len) {
                    chLocal = EOI;
                } else {
                    chLocal = text.charAt(index);
                }
            }
        }
        
        if (chLocal >= '0' && chLocal <= '9') {
            value = digits[chLocal];
            for (;;) {
                //chLocal = charAt(bp + (offset++));
                {
                    int index = bp + (offset++);
                    if (index >= len) {
                        chLocal = EOI;
                    } else {
                        chLocal = text.charAt(index);
                    }
                }
                if (chLocal >= '0' && chLocal <= '9') {
                    value = value * 10 + digits[chLocal];
                } else if (chLocal == '.') {
                    matchStat = NOT_MATCH;
                    return 0;
                } else if (chLocal == '\"') {
                    if (!quote) {
                        matchStat = NOT_MATCH;
                        return 0;
                    }
                    int index = bp + (offset++);
                    if (index >= len) {
                        chLocal = EOI;
                    } else {
                        chLocal = text.charAt(index);
                    }
                    break;
                } else {
                    break;
                }
            }
            if (value < 0) {
                matchStat = NOT_MATCH;
                return 0;
            }
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            // this.next();
            {
                int index = ++bp;
                if (index >= this.len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return value;
    }
    
    public String scanFieldString(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return stringDefaultValue();
        }

        // int index = bp + fieldName.length;

        int offset = fieldName.length;
        char chLocal;// = charAt(bp + (offset++));
        {
            int index = bp + (offset++);
            if (index >= len) {
                throw new JSONException("unclosed str");
            } else {
                chLocal = text.charAt(index);
            }
        }

        if (chLocal != '"') {
            matchStat = NOT_MATCH;

            return stringDefaultValue();
        }

        final String strVal;
        {
            int startIndex = bp + fieldName.length + 1;
            int endIndex = text.indexOf('"', startIndex);
            if (endIndex == -1) {
                throw new JSONException("unclosed str");
            }

            int startIndex2 = bp + fieldName.length + 1; // must re compute
            String stringVal = subString(startIndex2, endIndex - startIndex2);
            int firstSpecalIndex = -1;
            for (int i = 0, len = stringVal.length(); i < len; ++i) {
//            for (int i = bp + fieldName.length + 1; i < endIndex; ++i) {
//                if (charAt(i) == '\\') {
                if (stringVal.charAt(i) == '\\') {
                    firstSpecalIndex = i;
                    break;
                }
            }

            if (firstSpecalIndex != -1) {
                sp = 0;
                int i = bp + offset;
                for (; i < len; ++i) {
                    char ch = text.charAt(i);
                    
                    if (ch == '\"') {
                        break;
                    }
                    
                    if (ch == '\\') {
                        ++i;
                        ch = text.charAt(i);
                        if (i >= len){
                            matchStat = NOT_MATCH;
                            return stringDefaultValue();
                        }
                        ch = text.charAt(i);
                        switch (ch) {
                            case '0':
                                putChar('\0');
                                break;
                            case '1':
                                putChar('\1');
                                break;
                            case '2':
                                putChar('\2');
                                break;
                            case '3':
                                putChar('\3');
                                break;
                            case '4':
                                putChar('\4');
                                break;
                            case '5':
                                putChar('\5');
                                break;
                            case '6':
                                putChar('\6');
                                break;
                            case '7':
                                putChar('\7');
                                break;
                            case 'b': // 8
                                putChar('\b');
                                break;
                            case 't': // 9
                                putChar('\t');
                                break;
                            case 'n': // 10
                                putChar('\n');
                                break;
                            case 'v': // 11
                                putChar('\u000B');
                                break;
                            case 'f': // 12
                            case 'F':
                                putChar('\f');
                                break;
                            case 'r': // 13
                                putChar('\r');
                                break;
                            case '"': // 34
                                putChar('"');
                                break;
                            case '\'': // 39
                                putChar('\'');
                                break;
                            case '/': // 47
                                putChar('/');
                                break;
                            case '\\': // 92
                                putChar('\\');
                                break;
                            case 'x':
                                if (i + 2 >= len){
                                    matchStat = NOT_MATCH;
                                    return stringDefaultValue();
                                }
                                char x1 = ch = text.charAt(i + 1);
                                char x2 = ch = text.charAt(i + 2);
                                i += 2;

                                int x_val = digits[x1] * 16 + digits[x2];
                                char x_char = (char) x_val;
                                putChar(x_char);
                                break;
                            case 'u':
                                if (i + 4 >= len){
                                    matchStat = NOT_MATCH;
                                    return stringDefaultValue();
                                }
                                char u1 = ch = text.charAt(i + 1);
                                char u2 = ch = text.charAt(i + 2);
                                char u3 = ch = text.charAt(i + 3);
                                char u4 = ch = text.charAt(i + 4);
                                i += 4;
                                int val = Integer.parseInt(new String(new char[] { u1, u2, u3, u4 }), 16);
                                putChar((char) val);
                                break;
                            default:
                                throw new JSONException("unclosed string : " + ch);
                        }
                    } else {
                        putChar(ch);
                    }
                }
                this.hasSpecial = true;
                strVal = stringVal();
                
                offset = (i - bp) + 1;
                chLocal = charAt(bp + (offset++));
            } else {
                offset += (endIndex - (bp + fieldName.length + 1) + 1);
                // chLocal = charAt(bp + (offset++));
                {
                    int index = bp + (offset++);
                    if (index >= len) {
                        throw new JSONException("unclosed str");
                    } else {
                        chLocal = text.charAt(index);
                    }
                }
                strVal = stringVal;
            }
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            // this.next();
            {
                int index = ++bp;
                if (index >= this.len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
            matchStat = VALUE;
            return strVal;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return stringDefaultValue();
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return stringDefaultValue();
        }

        return strVal;
    }
    
    public boolean scanFieldBoolean(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return false;
        }

        int offset = fieldName.length;
        char chLocal; // = charAt(bp + (offset++));
        {
            int index = bp + (offset++);
            if (index >= len) {
                throw new JSONException("unclosed str");
            } else {
                chLocal = text.charAt(index);
            }
        }
        
        boolean quote = false;
        if (chLocal == '"') {
            quote = true;
            
            {
                int index = bp + (offset++);
                if (index >= len) {
                    chLocal = EOI;
                } else {
                    chLocal = text.charAt(index);
                }
            }
        }

        boolean value;
        if (chLocal == 't') {
            if (charAt(bp + (offset++)) != 'r') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'u') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'e') {
                matchStat = NOT_MATCH;
                return false;
            }
            
            chLocal = charAt(bp + (offset));
            if (chLocal == '\"') {
                if (!quote) {
                    matchStat = NOT_MATCH;
                    return false;
                }
                int index = bp + (offset++);
                if (index >= len) {
                    chLocal = EOI;
                } else {
                    chLocal = text.charAt(index);
                }
            }

            value = true;
        } else if (chLocal == 'f') {
            if (charAt(bp + (offset++)) != 'a') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'l') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 's') {
                matchStat = NOT_MATCH;
                return false;
            }
            if (charAt(bp + (offset++)) != 'e') {
                matchStat = NOT_MATCH;
                return false;
            }
            
            chLocal = charAt(bp + (offset));
            
            if (chLocal == '\"') {
                if (!quote) {
                    matchStat = NOT_MATCH;
                    return false;
                }
                int index = bp + (offset++);
                if (index >= len) {
                    chLocal = EOI;
                } else {
                    chLocal = text.charAt(index);
                }
            }

            value = false;
        } else {
            matchStat = NOT_MATCH;
            return false;
        }

        chLocal = charAt(bp + offset++);
        if (chLocal == ',') {
            bp += (offset - 1);
            // this.next();
            {
                int index = ++bp;
                if (index >= this.len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
            matchStat = VALUE;
            token = JSONToken.COMMA;

            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                // this.next();
                {
                    int index = ++bp;
                    if (index >= this.len) {
                        this.ch = EOI;
                    } else {
                        this.ch = this.text.charAt(index);
                    }
                }
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return false;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return false;
        }

        return value;
    }
    
    public final float scanFieldFloat(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        float value;
        if (chLocal >= '0' && chLocal <= '9') {
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    continue;
                } else {
                    break;
                }
            }

            if (chLocal == '.') {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    for (;;) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }

            int start = bp + fieldName.length;
            int count = bp + offset - start - 1;
            String text = this.subString(start, count);
            value = Float.parseFloat(text);
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                bp += (offset - 1);
                token = JSONToken.EOF;
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return value;
    }
    
    public final double scanFieldDouble(char[] fieldName) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return 0;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        double value;
        if (chLocal >= '0' && chLocal <= '9') {
            for (;;) {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    continue;
                } else {
                    break;
                }
            }

            if (chLocal == '.') {
                chLocal = charAt(bp + (offset++));
                if (chLocal >= '0' && chLocal <= '9') {
                    for (;;) {
                        chLocal = charAt(bp + (offset++));
                        if (chLocal >= '0' && chLocal <= '9') {
                            continue;
                        } else {
                            break;
                        }
                    }
                } else {
                    matchStat = NOT_MATCH;
                    return 0;
                }
            }

            if (chLocal == 'e' || chLocal == 'E') {
                chLocal = charAt(bp + (offset++));
                if (chLocal == '+' || chLocal == '-') {
                    chLocal = charAt(bp + (offset++));
                }
                for (;;) {
                    if (chLocal >= '0' && chLocal <= '9') {
                        chLocal = charAt(bp + (offset++));
                    } else {
                        break;
                    }
                }
            }

            int start = bp + fieldName.length;
            int count = bp + offset - start - 1;
            String text = this.subString(start, count);
            value = Double.parseDouble(text);
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            this.next();
            matchStat = VALUE;
            token = JSONToken.COMMA;
            return value;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return 0;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return 0;
        }

        return value;
    }
    
    public String scanFieldSymbol(char[] fieldName, final SymbolTable symbolTable) {
        matchStat = UNKNOWN;

        if (!charArrayCompare(fieldName)) {
            matchStat = NOT_MATCH_NAME;
            return null;
        }

        int offset = fieldName.length;
        char chLocal = charAt(bp + (offset++));

        if (chLocal != '"') {
            matchStat = NOT_MATCH;
            return null;
        }

        String strVal;

        int hash = 0;
        for (;;) {
            chLocal = charAt(bp + (offset++));
            if (chLocal == '\"') {
                int start = bp + fieldName.length + 1;
                int len = bp + offset - start - 1;
                strVal = symbolTable.addSymbol(text, start, len, hash);                    
                chLocal = charAt(bp + (offset++));
                break;
            }

            hash = 31 * hash + chLocal;

            if (chLocal == '\\') {
                matchStat = NOT_MATCH;
                return null;
            }
        }

        if (chLocal == ',') {
            bp += (offset - 1);
            // this.next();
            {
                int index = ++bp;
                if (index >= this.len) {
                    this.ch = EOI;
                } else {
                    this.ch = this.text.charAt(index);
                }
            }
            matchStat = VALUE;
            return strVal;
        }

        if (chLocal == '}') {
            chLocal = charAt(bp + (offset++));
            if (chLocal == ',') {
                token = JSONToken.COMMA;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == ']') {
                token = JSONToken.RBRACKET;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == '}') {
                token = JSONToken.RBRACE;
                bp += (offset - 1);
                this.next();
            } else if (chLocal == EOI) {
                token = JSONToken.EOF;
                bp += (offset - 1);
                ch = EOI;
            } else {
                matchStat = NOT_MATCH;
                return null;
            }
            matchStat = END;
        } else {
            matchStat = NOT_MATCH;
            return null;
        }

        return strVal;
    }
    
    static final int ISO8601_LEN_0 = "0000-00-00".length();
    static final int ISO8601_LEN_1 = "0000-00-00T00:00:00".length();
    static final int ISO8601_LEN_2 = "0000-00-00T00:00:00.000".length();

    public boolean scanISO8601DateIfMatch(boolean strict) {
        int rest = text.length() - bp;

        if ((!strict) && rest > 13) {
            char c0 = charAt(bp);
            char c1 = charAt(bp + 1);
            char c2 = charAt(bp + 2);
            char c3 = charAt(bp + 3);
            char c4 = charAt(bp + 4);
            char c5 = charAt(bp + 5);

            char c_r0 = charAt(bp + rest - 1);
            char c_r1 = charAt(bp + rest - 2);
            if (c0 == '/' && c1 == 'D' && c2 == 'a' && c3 == 't' && c4 == 'e' && c5 == '(' && c_r0 == '/'
                && c_r1 == ')') {
                int plusIndex = -1;
                for (int i = 6; i < rest; ++i) {
                    char c = charAt(bp + i);
                    if (c == '+') {
                        plusIndex = i;
                    } else if (c < '0' || c > '9') {
                        break;
                    }
                }
                if (plusIndex == -1) {
                    return false;
                }
                int offset = bp + 6;
                String numberText = this.subString(offset, plusIndex - offset);
                long millis = Long.parseLong(numberText);

                Locale local = Locale.getDefault();
                calendar = Calendar.getInstance(TimeZone.getDefault(), local);
                calendar.setTimeInMillis(millis);

                token = JSONToken.LITERAL_ISO8601_DATE;
                return true;
            }
        }

        if (rest == 8 || rest == 14 || rest == 17) {
            if (strict) {
                return false;
            }

            char y0 = charAt(bp);
            char y1 = charAt(bp + 1);
            char y2 = charAt(bp + 2);
            char y3 = charAt(bp + 3);
            char M0 = charAt(bp + 4);
            char M1 = charAt(bp + 5);
            char d0 = charAt(bp + 6);
            char d1 = charAt(bp + 7);

            if (!checkDate(y0, y1, y2, y3, M0, M1, d0, d1)) {
                return false;
            }

            setCalendar(y0, y1, y2, y3, M0, M1, d0, d1);

            int hour, minute, seconds, millis;
            if (rest != 8) {
                char h0 = charAt(bp + 8);
                char h1 = charAt(bp + 9);
                char m0 = charAt(bp + 10);
                char m1 = charAt(bp + 11);
                char s0 = charAt(bp + 12);
                char s1 = charAt(bp + 13);

                if (!checkTime(h0, h1, m0, m1, s0, s1)) {
                    return false;
                }

                if (rest == 17) {
                    char S0 = charAt(bp + 14);
                    char S1 = charAt(bp + 15);
                    char S2 = charAt(bp + 16);
                    if (S0 < '0' || S0 > '9') {
                        return false;
                    }
                    if (S1 < '0' || S1 > '9') {
                        return false;
                    }
                    if (S2 < '0' || S2 > '9') {
                        return false;
                    }

                    millis = digits[S0] * 100 + digits[S1] * 10 + digits[S2];
                } else {
                    millis = 0;
                }

                hour = digits[h0] * 10 + digits[h1];
                minute = digits[m0] * 10 + digits[m1];
                seconds = digits[s0] * 10 + digits[s1];
            } else {
                hour = 0;
                minute = 0;
                seconds = 0;
                millis = 0;
            }

            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, seconds);
            calendar.set(Calendar.MILLISECOND, millis);

            token = JSONToken.LITERAL_ISO8601_DATE;
            return true;
        }

        if (rest < ISO8601_LEN_0) {
            return false;
        }

        if (charAt(bp + 4) != '-') {
            return false;
        }
        if (charAt(bp + 7) != '-') {
            return false;
        }

        char y0 = charAt(bp);
        char y1 = charAt(bp + 1);
        char y2 = charAt(bp + 2);
        char y3 = charAt(bp + 3);
        char M0 = charAt(bp + 5);
        char M1 = charAt(bp + 6);
        char d0 = charAt(bp + 8);
        char d1 = charAt(bp + 9);
        if (!checkDate(y0, y1, y2, y3, M0, M1, d0, d1)) {
            return false;
        }

        setCalendar(y0, y1, y2, y3, M0, M1, d0, d1);

        char t = charAt(bp + 10);
        if (t == 'T' || (t == ' ' && !strict)) {
            if (rest < ISO8601_LEN_1) {
                return false;
            }
        } else if (t == '"' || t == EOI) {
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);

            ch = charAt(bp += 10);

            token = JSONToken.LITERAL_ISO8601_DATE;
            return true;
        } else {
            return false;
        }

        if (charAt(bp + 13) != ':') {
            return false;
        }
        if (charAt(bp + 16) != ':') {
            return false;
        }

        char h0 = charAt(bp + 11);
        char h1 = charAt(bp + 12);
        char m0 = charAt(bp + 14);
        char m1 = charAt(bp + 15);
        char s0 = charAt(bp + 17);
        char s1 = charAt(bp + 18);

        if (!checkTime(h0, h1, m0, m1, s0, s1)) {
            return false;
        }

        int hour = digits[h0] * 10 + digits[h1];
        int minute = digits[m0] * 10 + digits[m1];
        int seconds = digits[s0] * 10 + digits[s1];
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, seconds);

        char dot = charAt(bp + 19);
        if (dot == '.') {
            if (rest < ISO8601_LEN_2) {
                return false;
            }
        } else {
            calendar.set(Calendar.MILLISECOND, 0);

            ch = charAt(bp += 19);

            token = JSONToken.LITERAL_ISO8601_DATE;
                        
            if (dot == 'Z') {// UTC
                // bugfix https://github.com/alibaba/fastjson/issues/376
                if (calendar.getTimeZone().getRawOffset() != 0) {
                    String[] timeZoneIDs = TimeZone.getAvailableIDs(0);// 没有+ 和 - 默认相对0
                    if (timeZoneIDs.length > 0) {
                        TimeZone timeZone = TimeZone.getTimeZone(timeZoneIDs[0]);
                        calendar.setTimeZone(timeZone);
                    }
                }
            }
            return true;
        }

        
        char S0 = charAt(bp + 20);
        if (S0 < '0' || S0 > '9') {
            return false;
        }
        int millis = digits[S0];
        int millisLen = 1;
        
        {
            char S1 = charAt(bp + 21);
            if (S1 >= '0' && S1 <= '9') {
                millis = millis * 10 + digits[S1];
                millisLen = 2;
            }
        }

        if (millisLen == 2) {
            char S2 = charAt(bp + 22);
            if (S2 >= '0' && S2 <= '9') {
                millis = millis * 10 + digits[S2];
                millisLen = 3;
            }
        }

        calendar.set(Calendar.MILLISECOND, millis);

        int timzeZoneLength = 0;
        char timeZoneFlag = charAt(bp + 20 + millisLen);
        if (timeZoneFlag == '+' || timeZoneFlag == '-') {
           char t0 = charAt(bp + 20 + millisLen + 1);
           if (t0 < '0' || t0 > '1') {
               return false;
           }
           
           char t1 = charAt(bp + 20 + millisLen + 2);
           if (t1 < '0' || t1 > '9') {
               return false;
           }
           
           char t2 = charAt(bp + 20 + millisLen + 3);
           if (t2 == ':') { // ThreeLetterISO8601TimeZone
               char t3 = charAt(bp + 20 + millisLen + 4);
               if (t3 != '0') {
                   return false;
               }
               
               char t4 = charAt(bp + 20 + millisLen + 5);
               if (t4 != '0') {
                   return false;
               }
               timzeZoneLength = 6;
           } else if (t2 == '0') { //TwoLetterISO8601TimeZone
               char t3 = charAt(bp + 20 + millisLen + 4);
               if (t3 != '0') {
                   return false;
               }
               timzeZoneLength = 5;
           } else {
               timzeZoneLength = 3;
           }
           
           int timeZoneOffset = (digits[t0] * 10 + digits[t1]) * 3600 * 1000;
           if (timeZoneFlag == '-') {
               timeZoneOffset = -timeZoneOffset;
           }
           
           if (calendar.getTimeZone().getRawOffset() != timeZoneOffset) {
               String[] timeZoneIDs = TimeZone.getAvailableIDs(timeZoneOffset);
               if (timeZoneIDs.length > 0) {
                   TimeZone timeZone = TimeZone.getTimeZone(timeZoneIDs[0]);
                   calendar.setTimeZone(timeZone);
               }
           }
           
        } else if (timeZoneFlag == 'Z') {// UTC
            timzeZoneLength = 1;
            if (calendar.getTimeZone().getRawOffset() != 0) {
                String[] timeZoneIDs = TimeZone.getAvailableIDs(0);
                if (timeZoneIDs.length > 0) {
                    TimeZone timeZone = TimeZone.getTimeZone(timeZoneIDs[0]);
                    calendar.setTimeZone(timeZone);
                }
            }
        }
        
        char end = charAt(bp + (20 + millisLen + timzeZoneLength)) ;
        if (end != EOI && end != '"') {
            return false;
        }
        ch = charAt(bp += (20 + millisLen + timzeZoneLength));

        token = JSONToken.LITERAL_ISO8601_DATE;
        return true;
    }

    static boolean checkTime(char h0, char h1, char m0, char m1, char s0, char s1) {
        if (h0 == '0') {
            if (h1 < '0' || h1 > '9') {
                return false;
            }
        } else if (h0 == '1') {
            if (h1 < '0' || h1 > '9') {
                return false;
            }
        } else if (h0 == '2') {
            if (h1 < '0' || h1 > '4') {
                return false;
            }
        } else {
            return false;
        }

        if (m0 >= '0' && m0 <= '5') {
            if (m1 < '0' || m1 > '9') {
                return false;
            }
        } else if (m0 == '6') {
            if (m1 != '0') {
                return false;
            }
        } else {
            return false;
        }

        if (s0 >= '0' && s0 <= '5') {
            if (s1 < '0' || s1 > '9') {
                return false;
            }
        } else if (s0 == '6') {
            if (s1 != '0') {
                return false;
            }
        } else {
            return false;
        }

        return true;
    }

    private void setCalendar(char y0, char y1, char y2, char y3, char M0, char M1, char d0, char d1) {
        Locale local = Locale.getDefault();
        calendar = Calendar.getInstance(TimeZone.getDefault(), local);
        int year = digits[y0] * 1000 + digits[y1] * 100 + digits[y2] * 10 + digits[y3];
        int month = digits[M0] * 10 + digits[M1] - 1;
        int day = digits[d0] * 10 + digits[d1];
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month);
        calendar.set(Calendar.DAY_OF_MONTH, day);
    }

    static boolean checkDate(char y0, char y1, char y2, char y3, char M0, char M1, int d0, int d1) {
        if (y0 != '1' && y0 != '2') {
            return false;
        }
        if (y1 < '0' || y1 > '9') {
            return false;
        }
        if (y2 < '0' || y2 > '9') {
            return false;
        }
        if (y3 < '0' || y3 > '9') {
            return false;
        }

        if (M0 == '0') {
            if (M1 < '1' || M1 > '9') {
                return false;
            }
        } else if (M0 == '1') {
            if (M1 != '0' && M1 != '1' && M1 != '2') {
                return false;
            }
        } else {
            return false;
        }

        if (d0 == '0') {
            if (d1 < '1' || d1 > '9') {
                return false;
            }
        } else if (d0 == '1' || d0 == '2') {
            if (d1 < '0' || d1 > '9') {
                return false;
            }
        } else if (d0 == '3') {
            if (d1 != '0' && d1 != '1') {
                return false;
            }
        } else {
            return false;
        }

        return true;
    }

    
    
    /////////// base 64
    public static final char[] CA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    static final int[]  IA = new int[256];
    static {
        Arrays.fill(IA, -1);
        for (int i = 0, iS = CA.length; i < iS; i++) {
            IA[CA[i]] = i;
        }
        IA['='] = 0;
    }

    /**
     * Decodes a BASE64 encoded char array that is known to be resonably well formatted. The method is about twice as
     * fast as #decode(char[]). The preconditions are:<br>
     * + The array must have a line length of 76 chars OR no line separators at all (one line).<br>
     * + Line separator must be "\r\n", as specified in RFC 2045 + The array must not contain illegal characters within
     * the encoded string<br>
     * + The array CAN have illegal characters at the beginning and end, those will be dealt with appropriately.<br>
     * 
     * @param chars The source array. Length 0 will return an empty array. <code>null</code> will throw an exception.
     * @return The decoded array of bytes. May be of length 0.
     */
    public final static byte[] decodeFast(char[] chars, int offset, int charsLen) {
        // Check special case
        if (charsLen == 0) {
            return new byte[0];
        }

        int sIx = offset, eIx = offset + charsLen - 1; // Start and end index after trimming.

        // Trim illegal chars from start
        while (sIx < eIx && IA[chars[sIx]] < 0)
            sIx++;

        // Trim illegal chars from end
        while (eIx > 0 && IA[chars[eIx]] < 0)
            eIx--;

        // get the padding count (=) (0, 1 or 2)
        int pad = chars[eIx] == '=' ? (chars[eIx - 1] == '=' ? 2 : 1) : 0; // Count '=' at end.
        int cCnt = eIx - sIx + 1; // Content count including possible separators
        int sepCnt = charsLen > 76 ? (chars[76] == '\r' ? cCnt / 78 : 0) << 1 : 0;

        int len = ((cCnt - sepCnt) * 6 >> 3) - pad; // The number of decoded bytes
        byte[] bytes = new byte[len]; // Preallocate byte[] of exact length

        // Decode all but the last 0 - 2 bytes.
        int d = 0;
        for (int cc = 0, eLen = (len / 3) * 3; d < eLen;) {
            // Assemble three bytes into an int from four "valid" characters.
            int i = IA[chars[sIx++]] << 18 | IA[chars[sIx++]] << 12 | IA[chars[sIx++]] << 6 | IA[chars[sIx++]];

            // Add the bytes
            bytes[d++] = (byte) (i >> 16);
            bytes[d++] = (byte) (i >> 8);
            bytes[d++] = (byte) i;

            // If line separator, jump over it.
            if (sepCnt > 0 && ++cc == 19) {
                sIx += 2;
                cc = 0;
            }
        }

        if (d < len) {
            // Decode last 1-3 bytes (incl '=') into 1-3 bytes
            int i = 0;
            for (int j = 0; sIx <= eIx - pad; j++)
                i |= IA[chars[sIx++]] << (18 - j * 6);

            for (int r = 16; d < len; r -= 8)
                bytes[d++] = (byte) (i >> r);
        }

        return bytes;
    }
    
    public final static byte[] decodeFast(String chars, int offset, int charsLen) {
        // Check special case
        if (charsLen == 0) {
            return new byte[0];
        }

        int sIx = offset, eIx = offset + charsLen - 1; // Start and end index after trimming.

        // Trim illegal chars from start
        while (sIx < eIx && IA[chars.charAt(sIx)] < 0)
            sIx++;

        // Trim illegal chars from end
        while (eIx > 0 && IA[chars.charAt(eIx)] < 0)
            eIx--;

        // get the padding count (=) (0, 1 or 2)
        int pad = chars.charAt(eIx) == '=' ? (chars.charAt(eIx - 1) == '=' ? 2 : 1) : 0; // Count '=' at end.
        int cCnt = eIx - sIx + 1; // Content count including possible separators
        int sepCnt = charsLen > 76 ? (chars.charAt(76) == '\r' ? cCnt / 78 : 0) << 1 : 0;

        int len = ((cCnt - sepCnt) * 6 >> 3) - pad; // The number of decoded bytes
        byte[] bytes = new byte[len]; // Preallocate byte[] of exact length

        // Decode all but the last 0 - 2 bytes.
        int d = 0;
        for (int cc = 0, eLen = (len / 3) * 3; d < eLen;) {
            // Assemble three bytes into an int from four "valid" characters.
            int i = IA[chars.charAt(sIx++)] << 18 | IA[chars.charAt(sIx++)] << 12 | IA[chars.charAt(sIx++)] << 6 | IA[chars.charAt(sIx++)];

            // Add the bytes
            bytes[d++] = (byte) (i >> 16);
            bytes[d++] = (byte) (i >> 8);
            bytes[d++] = (byte) i;

            // If line separator, jump over it.
            if (sepCnt > 0 && ++cc == 19) {
                sIx += 2;
                cc = 0;
            }
        }

        if (d < len) {
            // Decode last 1-3 bytes (incl '=') into 1-3 bytes
            int i = 0;
            for (int j = 0; sIx <= eIx - pad; j++)
                i |= IA[chars.charAt(sIx++)] << (18 - j * 6);

            for (int r = 16; d < len; r -= 8)
                bytes[d++] = (byte) (i >> r);
        }

        return bytes;
    }

    /**
     * Decodes a BASE64 encoded string that is known to be resonably well formatted. The method is about twice as fast
     * as decode(String). The preconditions are:<br>
     * + The array must have a line length of 76 chars OR no line separators at all (one line).<br>
     * + Line separator must be "\r\n", as specified in RFC 2045 + The array must not contain illegal characters within
     * the encoded string<br>
     * + The array CAN have illegal characters at the beginning and end, those will be dealt with appropriately.<br>
     * 
     * @param s The source string. Length 0 will return an empty array. <code>null</code> will throw an exception.
     * @return The decoded array of bytes. May be of length 0.
     */
    public final static byte[] decodeFast(String s) {
        // Check special case
        int sLen = s.length();
        if (sLen == 0) {
            return new byte[0];
        }

        int sIx = 0, eIx = sLen - 1; // Start and end index after trimming.

        // Trim illegal chars from start
        while (sIx < eIx && IA[s.charAt(sIx) & 0xff] < 0)
            sIx++;

        // Trim illegal chars from end
        while (eIx > 0 && IA[s.charAt(eIx) & 0xff] < 0)
            eIx--;

        // get the padding count (=) (0, 1 or 2)
        int pad = s.charAt(eIx) == '=' ? (s.charAt(eIx - 1) == '=' ? 2 : 1) : 0; // Count '=' at end.
        int cCnt = eIx - sIx + 1; // Content count including possible separators
        int sepCnt = sLen > 76 ? (s.charAt(76) == '\r' ? cCnt / 78 : 0) << 1 : 0;

        int len = ((cCnt - sepCnt) * 6 >> 3) - pad; // The number of decoded bytes
        byte[] dArr = new byte[len]; // Preallocate byte[] of exact length

        // Decode all but the last 0 - 2 bytes.
        int d = 0;
        for (int cc = 0, eLen = (len / 3) * 3; d < eLen;) {
            // Assemble three bytes into an int from four "valid" characters.
            int i = IA[s.charAt(sIx++)] << 18 | IA[s.charAt(sIx++)] << 12 | IA[s.charAt(sIx++)] << 6
                    | IA[s.charAt(sIx++)];

            // Add the bytes
            dArr[d++] = (byte) (i >> 16);
            dArr[d++] = (byte) (i >> 8);
            dArr[d++] = (byte) i;

            // If line separator, jump over it.
            if (sepCnt > 0 && ++cc == 19) {
                sIx += 2;
                cc = 0;
            }
        }

        if (d < len) {
            // Decode last 1-3 bytes (incl '=') into 1-3 bytes
            int i = 0;
            for (int j = 0; sIx <= eIx - pad; j++)
                i |= IA[s.charAt(sIx++)] << (18 - j * 6);

            for (int r = 16; d < len; r -= 8)
                dArr[d++] = (byte) (i >> r);
        }

        return dArr;
    }

    

    public final static boolean[] firstIdentifierFlags = new boolean[256];
    static {
        for (char c = 0; c < firstIdentifierFlags.length; ++c) {
            if (c >= 'A' && c <= 'Z') {
                firstIdentifierFlags[c] = true;
            } else if (c >= 'a' && c <= 'z') {
                firstIdentifierFlags[c] = true;
            } else if (c == '_') {
                firstIdentifierFlags[c] = true;
            }
        }
    }

    public final static boolean[] identifierFlags = new boolean[256];

    static {
        for (char c = 0; c < identifierFlags.length; ++c) {
            if (c >= 'A' && c <= 'Z') {
                identifierFlags[c] = true;
            } else if (c >= 'a' && c <= 'z') {
                identifierFlags[c] = true;
            } else if (c == '_') {
                identifierFlags[c] = true;
            } else if (c >= '0' && c <= '9') {
                identifierFlags[c] = true;
            }
        }
    }
    
//    @Override
//    public boolean isEOF() {
//        return bp == len || ch == EOI && bp + 1 == len;
//    }
}
