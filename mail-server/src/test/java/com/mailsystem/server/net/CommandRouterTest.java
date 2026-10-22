package com.mailsystem.server.net;

import com.mailsystem.common.protocol.Command;
import com.mailsystem.common.protocol.ResponseMessage;
import com.mailsystem.server.service.ServerServices;
import com.mailsystem.server.session.ClientSession;
import com.mailsystem.server.transfer.UploadManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRouterTest {

    private static final class AnonymousContext implements ConnectionContext {
        @Override
        public Integer accountId() {
            return null;
        }

        @Override
        public String remoteIp() {
            return "127.0.0.1";
        }

        @Override
        public UploadManager uploads() {
            return new UploadManager(Path.of("target", "tmp-uploads"));
        }

        @Override
        public ClientSession session() {
            return null;
        }

        @Override
        public void signIn(int accountId) {
        }

        @Override
        public void signOut() {
        }
    }

    @Test
    void everyCommandHasARouteAndOnlyPublicOnesSkipLogin() {
        CommandRouter router = new CommandRouter(ServerServices.createDefault());
        Set<Command> publicCommands = EnumSet.of(Command.PING, Command.REGISTER, Command.LOGIN);
        for (Command command : Command.values()) {
            if (publicCommands.contains(command)) {
                continue;
            }
            ResponseMessage response = router.dispatch(command, "id-" + command, null, new AnonymousContext());
            assertFalse(response.isOk(), command.name());
            assertEquals("Bạn chưa đăng nhập.", response.getMessage(), command.name());
        }
        assertTrue(router.dispatch(Command.PING, "p", null, new AnonymousContext()).isOk());
    }
}
