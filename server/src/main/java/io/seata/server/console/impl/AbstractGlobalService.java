/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.console.impl;

import io.seata.console.result.SingleResult;
import io.seata.core.model.GlobalStatus;
import io.seata.server.console.exception.ConsoleException;
import io.seata.server.console.service.GlobalSessionService;
import io.seata.server.coordinator.DefaultCoordinator;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHolder;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractGlobalService extends AbstractService implements GlobalSessionService {
    @Override
    public SingleResult<Void> deleteGlobalSession(String xid) {
        GlobalSession globalSession = checkGlobalSession(xid);
        GlobalStatus globalStatus = globalSession.getStatus();
        if (FAIL_STATUS.contains(globalStatus) || RETRY_STATUS.contains(globalStatus) || FINISH_STATUS.contains(globalStatus)
                || GlobalStatus.Deleting.equals(globalStatus) || GlobalStatus.StopCommitRetry.equals(globalStatus)
                || GlobalStatus.StopRollbackRetry.equals(globalStatus)) {
            try {
                if (!GlobalStatus.Deleting.equals(globalStatus)) {
                    globalSession.changeGlobalStatus(GlobalStatus.Deleting);
                }
                List<BranchSession> branchSessions = globalSession.getBranchSessions();
                List<BranchSession> iteratorBranchSessions = new ArrayList<>(branchSessions);
                for (BranchSession branchSession : iteratorBranchSessions) {
                    if (!doDeleteBranch(globalSession, branchSession)) {
                        return SingleResult.failure("Delete branch fail, please try again");
                    }
                }
                globalSession.end();
                return SingleResult.success();
            } catch (Exception e) {
                throw new ConsoleException(e, String.format("delete global session fail, xid:%s", xid));
            }
        }
        throw new IllegalArgumentException("current global transaction status is not support deleted");
    }

    @Override
    public SingleResult<Void> stopGlobalRetry(String xid) {
        GlobalSession globalSession = checkGlobalSession(xid);
        GlobalStatus globalStatus = globalSession.getStatus();
        GlobalStatus newStatus = RETRY_COMMIT_STATUS.contains(globalStatus) ||
                GlobalStatus.Committing.equals(globalStatus) ? GlobalStatus.StopCommitRetry :
                RETRY_ROLLBACK_STATUS.contains(globalStatus) ||
                        GlobalStatus.Rollbacking.equals(globalStatus) ? GlobalStatus.StopRollbackRetry : null;
        if (newStatus == null) {
            throw new IllegalArgumentException("current global transaction status is not support stop");
        }
        try {
            globalSession.changeGlobalStatus(newStatus);
            return SingleResult.success();
        } catch (Exception e) {
            throw new ConsoleException(e, String.format("Stop global session retry fail, xid:%s", xid));
        }
    }

    @Override
    public SingleResult<Void> startGlobalRetry(String xid) {
        GlobalSession globalSession = checkGlobalSession(xid);
        GlobalStatus globalStatus = globalSession.getStatus();
        GlobalStatus newStatus = GlobalStatus.StopCommitRetry.equals(globalStatus) ? GlobalStatus.CommitRetrying :
                GlobalStatus.StopRollbackRetry.equals(globalStatus) ? GlobalStatus.RollbackRetrying : null;
        if (newStatus == null) {
            throw new IllegalArgumentException("current global transaction status is not support start");
        }
        try {
            globalSession.changeGlobalStatus(newStatus);
            return SingleResult.success();
        } catch (Exception e) {
            throw new ConsoleException(e, String.format("Start global session retry fail, xid:%s", xid));
        }
    }

    @Override
    public SingleResult<Void> sendCommitOrRollback(String xid) {
        GlobalSession globalSession = checkGlobalSession(xid);
        GlobalStatus globalStatus = globalSession.getStatus();
        try {
            boolean res;
            if (RETRY_COMMIT_STATUS.contains(globalStatus) || GlobalStatus.Committing.equals(globalStatus)
                    || GlobalStatus.StopCommitRetry.equals(globalStatus)) {
                res = DefaultCoordinator.getInstance().getCore().doGlobalCommit(globalSession, false);
                if (res && globalSession.hasBranch() && globalSession.hasATBranch()) {
                    globalSession.clean();
                    globalSession.asyncCommit();
                } else if (res && SessionHolder.findGlobalSession(xid) != null) {
                    globalSession.end();
                }
            } else if (RETRY_ROLLBACK_STATUS.contains(globalStatus) || GlobalStatus.Rollbacking.equals(globalStatus)
                    || GlobalStatus.StopRollbackRetry.equals(globalStatus)) {
                res = DefaultCoordinator.getInstance().getCore().doGlobalRollback(globalSession, false);
                // the record is not deleted
                if (res && SessionHolder.findGlobalSession(xid) != null) {
                    globalSession.changeGlobalStatus(GlobalStatus.Rollbacked);
                    globalSession.end();
                }
            } else {
                throw new IllegalArgumentException("current global transaction status is not support to do");
            }
            return res ? SingleResult.success() :
                    SingleResult.failure("Commit or rollback fail, please try again");
        } catch (Exception e) {
            throw new ConsoleException(e, String.format("send commit or rollback to rm fail, xid:%s", xid));
        }
    }

    @Override
    public SingleResult<Void> changeGlobalStatus(String xid) {
        GlobalSession globalSession = checkGlobalSession(xid);
        GlobalStatus globalStatus = globalSession.getStatus();
        GlobalStatus newStatus = FAIL_COMMIT_STATUS.contains(globalStatus) ? GlobalStatus.CommitRetrying :
                FAIL_ROLLBACK_STATUS.contains(globalStatus) ? GlobalStatus.RollbackRetrying : null;
        if (newStatus == null) {
            throw new IllegalArgumentException("current global transaction status is not support to change");
        }
        try {
            globalSession.changeGlobalStatus(newStatus);
            return SingleResult.success();
        } catch (Exception e) {
            throw new ConsoleException(e, String.format("change global status fail, xid:%s", xid));
        }
    }
}