package dev.mjwatch.core

import org.junit.Assert.*
import org.junit.Test

class ServerUpdateTest {
 @Test fun distinguishesConfirmedRollbackFromUnknownHealth() {
  assertTrue(serverUpdateFailure("health_failed").contains("отката тоже не подтверждён"))
  assertTrue(serverUpdateFailure("update_failed_binary_rollback_confirmed").contains("подтвердил её работоспособность"))
  assertFalse(serverUpdateFailure("unexpected_code").contains("вернулся к прежней"))
 }
}
