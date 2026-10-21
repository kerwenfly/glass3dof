package com.rxz.glass3dof;

import android.view.Surface;

/**
 * Trusted virtual display service, running in the shell process provided by Shizuku.
 *
 * NOTE: Shizuku UserService is not an android.app.Service; it extends this Stub and is
 * instantiated by Shizuku inside app_process. destroy uses the agreed transaction id.
 *
 * Keep this file ASCII-only: aidl.exe on Windows emits non-ASCII comments in the system
 * code page, which then breaks javac's UTF-8 decoding of the generated stub.
 */
interface IProjectionService {

    /** Creates a virtual display whose output goes to surface. Returns displayId, -1 on failure. */
    int createDisplay(int width, int height, int dpi, int flags, in Surface surface) = 1;

    /** Releases the current virtual display. */
    void releaseDisplay() = 2;

    /** displayId of the current virtual display, -1 if none. */
    int getDisplayId() = 3;

    /** Self-check info (uid / context availability) for logging. */
    String info() = 4;

    /** Detailed failure reason of the last createDisplay call (empty on success). */
    String lastError() = 5;

    /** Destroy transaction agreed with Shizuku (max allowed transaction id). */
    void destroy() = 16777114;
}
