package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolResult
import com.fsaint.androidagent.model.VerificationState

class VerificationEngine { fun isVerified(result: ToolResult<*>): Boolean = result.success && result.verification == VerificationState.VERIFIED }
