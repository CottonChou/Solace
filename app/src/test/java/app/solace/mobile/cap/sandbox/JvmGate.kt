package app.solace.mobile.cap.sandbox

import app.solace.mobile.core.workspace.NioFdSys
import app.solace.mobile.core.workspace.WorkspaceFileGate

/** JVM 测试里的关口(按路径的近似;手机上是 AndroidFdSys.gate)。 */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
