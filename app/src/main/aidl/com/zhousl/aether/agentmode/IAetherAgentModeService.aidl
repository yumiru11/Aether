package com.zhousl.aether.agentmode;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IAetherAgentModeService {
    int createDisplay(String name, int width, int height, int density, in Surface surface) = 1;
    int createOwnedDisplay(String name, int width, int height, int density) = 2;
    void attachPreviewSurface(int displayId, in Surface surface) = 3;
    void detachPreviewSurface(int displayId) = 4;
    void releaseDisplay(int displayId) = 5;
    void launchPackage(String packageName, int displayId) = 6;
    void runInputCommand(String command) = 7;
    void tap(int displayId, int x, int y) = 8;
    void swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 9;
    void key(int displayId, String keyCode) = 10;
    String text(int displayId, String text) = 11;
    void captureImageToFd(int displayId, in ParcelFileDescriptor output, int maxEdge, int quality) = 12;
    String listDisplaysJson() = 13;
    String listInstalledAppsJson() = 14;
    String focusedWindowJson(int displayId) = 15;
    // The service process exits when [client] dies, so it never outlives the Aether process.
    void linkClient(IBinder client) = 16;

    // Element observation. Every entry point is additive: the capture and input methods above keep
    // their exact signatures so a pure-vision Agent Mode run behaves as it did before this interface
    // grew, and so a stale service process is rejected by binder versioning instead of half-working.
    //
    // [callerUser] and [serviceUser] travel back with each result because element reads happen in the
    // Android user the service process registered as, which is not necessarily the user whose apps
    // are on the display. Without them an empty read is indistinguishable from an empty screen.
    String elementCapabilitiesJson() = 17;
    String observeElementsJson(int displayId, String optionsJson) = 18;
    String elementActionJson(int displayId, String requestJson) = 19;
    String settleJson(int displayId, String optionsJson) = 20;
    // A region capture is a separate entry point rather than a new parameter on captureImageToFd:
    // the whole-screen path stays byte-for-byte identical, and a crop reports its own origin so the
    // model can convert an image pixel back into a display pixel without guessing at a scale factor.
    void captureRegionToFd(
        int displayId,
        in ParcelFileDescriptor output,
        int maxEdge,
        int quality,
        int left,
        int top,
        int right,
        int bottom
    ) = 21;
    // Called on the caller's binder thread so the automation connection can be torn down with the
    // identity it was registered under.
    void detachElementReader() = 22;

    void destroy() = 16777114;
}
