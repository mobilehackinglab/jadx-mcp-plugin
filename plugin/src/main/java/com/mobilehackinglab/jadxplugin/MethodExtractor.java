package com.mobilehackinglab.jadxplugin;

import jadx.api.JavaMethod;
import jadx.core.dex.info.AccessInfo;

public final class MethodExtractor {

    public static String extract(JavaMethod method, String classCode) {
        if (classCode == null || classCode.isEmpty()) {
            return null;
        }

        String methodName = method.getName();
        int targetArgCount = method.getArguments() == null ? 0 : method.getArguments().size();
        AccessInfo methodAccessFlags = method.getAccessFlags();

        int searchFrom = 0;
        while (true) {
            int idx = classCode.indexOf(methodName + "(", searchFrom);
            if (idx == -1) {
                return null;
            }

            int lineStart = classCode.lastIndexOf('\n', idx);
            if (lineStart < 0) {
                lineStart = 0;
            }

            int prev = idx - 1;
            char prevCh = prev >= 0 ? classCode.charAt(prev) : '\n';
            if (prevCh == '.' || Character.isJavaIdentifierPart(prevCh)) {
                searchFrom = idx + methodName.length() + 1;
                continue;
            }

            String prefix = classCode.substring(lineStart, idx).trim();
            if (prefix.contains(".")) {
                searchFrom = idx + methodName.length() + 1;
                continue;
            }

            if (!modifiersMatchPrefix(prefix, methodAccessFlags)) {
                searchFrom = idx + methodName.length() + 1;
                continue;
            }

            int parenDepth = 0;
            int pos = idx;
            int parenClose = -1;
            while (pos < classCode.length()) {
                char c = classCode.charAt(pos);
                if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                    if (parenDepth == 0) {
                        parenClose = pos;
                        break;
                    }
                }
                pos++;
            }
            if (parenClose == -1) {
                return null;
            }

            String params = classCode.substring(idx + methodName.length() + 1, parenClose);
            int parsedArgCount = countTopLevelParams(params);
            if (parsedArgCount != targetArgCount) {
                searchFrom = idx + methodName.length() + 1;
                continue;
            }

            int lookPos = parenClose + 1;
            while (lookPos < classCode.length()) {
                char c = classCode.charAt(lookPos);
                if (Character.isWhitespace(c)) {
                    lookPos++;
                    continue;
                }
                if (c == '{') {
                    int annStart = lineStart;
                    while (annStart > 0) {
                        int prevNl = classCode.lastIndexOf('\n', annStart - 1);
                        if (prevNl < 0) {
                            break;
                        }
                        String line = classCode.substring(prevNl + 1, annStart).trim();
                        if (line.startsWith("@")) {
                            annStart = prevNl;
                            continue;
                        }
                        break;
                    }

                    int depth = 0;
                    int i = lookPos;
                    while (i < classCode.length()) {
                        char bc = classCode.charAt(i++);
                        if (bc == '{') {
                            depth++;
                        } else if (bc == '}') {
                            depth--;
                            if (depth == 0) {
                                return classCode.substring(annStart == 0 ? annStart : annStart + 1, i);
                            }
                        }
                    }
                    return null;
                } else if (c == ';') {
                    int annStart = lineStart;
                    while (annStart > 0) {
                        int prevNl = classCode.lastIndexOf('\n', annStart - 1);
                        if (prevNl < 0) {
                            break;
                        }
                        String line = classCode.substring(prevNl + 1, annStart).trim();
                        if (line.startsWith("@")) {
                            annStart = prevNl;
                            continue;
                        }
                        break;
                    }
                    return classCode.substring(annStart == 0 ? annStart : annStart + 1, lookPos + 1);
                } else {
                    break;
                }
            }

            int nextNl = classCode.indexOf('\n', parenClose);
            if (nextNl == -1) {
                return null;
            }
            int afterNl = nextNl + 1;
            while (afterNl < classCode.length()) {
                int nl = classCode.indexOf('\n', afterNl);
                if (nl == -1) {
                    nl = classCode.length();
                }
                String line = classCode.substring(afterNl, nl).trim();
                if (line.isEmpty() || line.startsWith("@")) {
                    afterNl = nl + 1;
                    continue;
                }
                if (line.startsWith("{")) {
                    int braceOpen = classCode.indexOf('{', afterNl);
                    int depth = 0;
                    int i = braceOpen;
                    while (i < classCode.length()) {
                        char bc = classCode.charAt(i++);
                        if (bc == '{') {
                            depth++;
                        } else if (bc == '}') {
                            depth--;
                            if (depth == 0) {
                                return classCode.substring(lineStart == 0 ? lineStart : lineStart + 1, i);
                            }
                        }
                    }
                    return null;
                }
                if (line.endsWith(";")) {
                    return classCode.substring(lineStart == 0 ? lineStart : lineStart + 1, nl);
                }
                break;
            }

            searchFrom = idx + methodName.length() + 1;
        }
    }

    private static int countTopLevelParams(String params) {
        if (params == null) {
            return 0;
        }
        int count = 0;
        int len = params.length();
        int depth = 0;
        boolean inToken = false;
        for (int i = 0; i < len; i++) {
            char c = params.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',') {
                if (depth == 0) {
                    count++;
                    inToken = false;
                }
            } else if (!Character.isWhitespace(c)) {
                inToken = true;
            }
        }
        if (inToken) {
            count++;
        }
        return count;
    }

    private static boolean modifiersMatchPrefix(String prefix, AccessInfo flags) {
        if (flags == null || prefix == null) {
            return true;
        }
        String p = " " + prefix.toLowerCase() + " ";
        if (flags.isPublic() && !p.contains(" public ")) {
            return false;
        }
        if (flags.isProtected() && !p.contains(" protected ")) {
            return false;
        }
        if (flags.isPrivate() && !p.contains(" private ")) {
            return false;
        }
        if (flags.isStatic() && !p.contains(" static ")) {
            return false;
        }
        if (flags.isFinal() && !p.contains(" final ")) {
            return false;
        }
        if (flags.isSynchronized() && !p.contains(" synchronized ")) {
            return false;
        }
        if (flags.isNative() && !p.contains(" native ")) {
            return false;
        }
        if (flags.isAbstract() && !p.contains(" abstract ")) {
            return false;
        }
        return true;
    }
}
