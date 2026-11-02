package io.github.rwxzxy.guard.shizuku;

import android.os.Bundle;

// 在 Shizuku 创建的进程中运行（身份为 shell uid 2000 或 root uid 0）。
interface IShellService {
    // Shizuku 约定的销毁方法，事务码 16777115（AIDL 中写 16777114）。
    void destroy() = 16777114;

    // 执行命令：argv 直接交给 ProcessBuilder，不经过 shell 解析；stdin 可为空。
    // 返回 Bundle：exit(int) / out(String) / err(String) / timeout(boolean)。
    Bundle execute(in String[] argv, String stdin, long timeoutMs) = 1;

    // 服务进程的 uid，用于确认身份（2000 = adb shell，0 = root）。
    int processUid() = 2;
}
