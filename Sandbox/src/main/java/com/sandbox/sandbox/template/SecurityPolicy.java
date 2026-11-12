package com.sandbox.sandbox.template;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * 第一层安全策略（静态检查）：黑名单 + 长度限制 + 用例数量限制。
 * 注意：这只是“弱安全”。生产必须配合 Docker/cgroup/seccomp 等强隔离。
 */
public class SecurityPolicy {
    private final int maxCodeLen;
    private final int maxCaseCount;
    private final Map<String, String> javaRules = new LinkedHashMap<>();
    private final Map<String, String> pythonRules = new LinkedHashMap<>();
    private final Map<String, String> cppRules = new LinkedHashMap<>();
    public SecurityPolicy(int maxCodeLen, int maxCaseCount) {
        this.maxCodeLen = maxCodeLen;
        this.maxCaseCount = maxCaseCount;
        // Java（模板默认语言）
        javaRules.put("Runtime.getRuntime", "禁止执行系统命令或外部程序");
        javaRules.put("ProcessBuilder", "禁止创建子进程");
        javaRules.put("java.lang.Process", "禁止直接操作操作系统进程");
        javaRules.put("java.net.", "禁止网络访问");
        javaRules.put("java.nio.file", "禁止文件系统访问（NIO）");
        javaRules.put("java.io.File", "禁止文件系统访问（IO）");
        javaRules.put("System.exit", "禁止结束 JVM");
        javaRules.put("Class.forName", "禁止通过反射/类加载绕过限制（教学版拦截）");
        // Python（静态拦截：教学版，生产必须配合 Docker 强隔离）
        pythonRules.put("import os", "禁止系统/进程相关操作（os）");
        pythonRules.put("from os", "禁止系统/进程相关操作（os）");
        pythonRules.put("import subprocess", "禁止创建子进程（subprocess）");
        pythonRules.put("from subprocess", "禁止创建子进程（subprocess）");
        pythonRules.put("import socket", "禁止网络访问（socket）");
        pythonRules.put("from socket", "禁止网络访问（socket）");
        pythonRules.put("requests.", "禁止网络访问（requests）");
        pythonRules.put("import requests", "禁止网络访问（requests）");
        pythonRules.put("__import__", "禁止动态导入绕过限制（__import__）");
        pythonRules.put("eval(", "禁止动态执行（eval）");
        pythonRules.put("exec(", "禁止动态执行（exec）");
        // 图像题需要 Pillow/cv2 读取沙箱内的只读题图。容器仍断网、只读根文件系统并丢弃全部 capability。
        // C++（静态拦截：教学版）
        cppRules.put("system(", "禁止执行系统命令（system）");
        cppRules.put("popen(", "禁止执行系统命令（popen）");
        cppRules.put("fork(", "禁止创建子进程（fork）");
        cppRules.put("execve(", "禁止创建子进程（execve）");
        cppRules.put("execvp(", "禁止创建子进程（execvp）");
        cppRules.put("execl(", "禁止创建子进程（execl）");
        cppRules.put("socket(", "禁止网络访问（socket）");
        cppRules.put("#include <sys/socket.h>", "禁止网络访问（socket）");
        cppRules.put("#include <netinet/in.h>", "禁止网络访问（socket）");
        cppRules.put("#include <arpa/inet.h>", "禁止网络访问（socket）");
        cppRules.put("fopen(", "禁止文件读写（fopen）");
        cppRules.put("#include <fstream>", "禁止文件读写（fstream）");
        cppRules.put("std::fstream", "禁止文件读写（fstream）");
        cppRules.put("std::ifstream", "禁止文件读写（ifstream）");
        cppRules.put("std::ofstream", "禁止文件读写（ofstream）");
    }
    public void check(SandboxContext ctx) {
        String code = ctx.request.getCode();
        if (code.length() > maxCodeLen) {
            throw new SandboxException(SandboxError.SECURITY_REJECT, "安全检查未通过：代码过长", "code too long");
        }
        if (ctx.inputList != null && ctx.inputList.size() > maxCaseCount) {
            throw new SandboxException(SandboxError.SECURITY_REJECT, "安全检查未通过：测试用例过多", "too many cases");
        }
        String lang = ctx.request.getLanguage() == null ? "" : ctx.request.getLanguage().trim().toLowerCase();
        // The source scanner is a secondary guard. Comments and string literals are
        // not executable Python code and must not reject otherwise valid submissions.
        String checkedCode = "python".equals(lang) ? pythonCodeWithoutCommentsAndStrings(code) : code;
        Map<String, String> rules = switch (lang) {
            case "python" -> pythonRules;
            case "cpp", "c++" -> cppRules;
            default -> javaRules;
        };
        for (Map.Entry<String, String> e : rules.entrySet()) {
            if (checkedCode.contains(e.getKey())) {
                String readable = e.getValue();
                throw new SandboxException(
                        SandboxError.SECURITY_REJECT,
                        "安全检查未通过：" + readable,
                        readable + "（匹配片段：" + e.getKey() + "）"
                );
            }
        }
    }

    private static String pythonCodeWithoutCommentsAndStrings(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int length = source.length();
        for (int i = 0; i < length;) {
            char c = source.charAt(i);
            if (c == '#') {
                while (i < length && source.charAt(i) != '\n') {
                    result.append(' ');
                    i++;
                }
                continue;
            }
            if (c != '\'' && c != '"') {
                result.append(c);
                i++;
                continue;
            }
            boolean triple = i + 2 < length && source.charAt(i + 1) == c && source.charAt(i + 2) == c;
            int quoteLength = triple ? 3 : 1;
            for (int j = 0; j < quoteLength; j++) result.append(' ');
            i += quoteLength;
            while (i < length) {
                char current = source.charAt(i);
                if (current == '\\' && i + 1 < length) {
                    result.append(' ');
                    result.append(source.charAt(i + 1) == '\n' ? '\n' : ' ');
                    i += 2;
                    continue;
                }
                if (current == c && (!triple || (i + 2 < length
                        && source.charAt(i + 1) == c && source.charAt(i + 2) == c))) {
                    for (int j = 0; j < quoteLength; j++) result.append(' ');
                    i += quoteLength;
                    break;
                }
                result.append(current == '\n' ? '\n' : ' ');
                i++;
            }
        }
        return result.toString();
    }
}
