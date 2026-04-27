// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.recorder

/**
 * Coarse Shizuku state, suitable for driving onboarding UI.
 *
 *  - [NotRunning]: shizuku-server isn't reachable. User must open the Shizuku
 *    app and start the service via Wireless Debugging or root.
 *  - [Outdated]: pre-V11 binary; modern user-service APIs absent.
 *  - [NeedPermission]: server is up, but our package hasn't asked or the user
 *    just denied.
 *  - [Denied]: explicit denial; we should explain how to revoke it.
 *  - [Ready]: bound and permitted; the [ShizukuClient] can call user-service.
 */
enum class ShizukuState { NotRunning, Outdated, NeedPermission, Denied, Ready }
