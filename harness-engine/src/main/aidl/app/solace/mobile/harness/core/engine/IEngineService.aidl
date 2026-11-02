package app.solace.mobile.harness.core.engine;

interface IEngineService {
    /**
     * Starts Node + the harness once per process. Returns a Bundle with ParcelFileDescriptors:
     * "sdk_in" (write end of harness stdin), "sdk_out" (read end of harness stdout), "bridge" (capability socket).
     * Throws if the engine already ran in this process (Node can only start once per process).
     */
    Bundle start(in Bundle env);
    boolean isRunning();
}
