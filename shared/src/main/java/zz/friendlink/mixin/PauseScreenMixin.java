package zz.friendlink.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zz.friendlink.gui.P2PConnectScreen;
import zz.friendlink.gui.P2PUiActions;
import zz.friendlink.i18n.P2PTexts;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void friendLink$addButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(P2PTexts.c("button.p2p_host"), button -> {
                button.setMessage(P2PTexts.c("button.listening"));
                button.active = false;
                P2PUiActions.listen(this.minecraft, message -> {
                    if (!message.startsWith(P2PTexts.s("status.signaling_connecting"))) {
                        button.active = true;
                    }
                    button.setMessage(message.startsWith(P2PTexts.s("status.listen_success"))
                        ? P2PTexts.c("status.listen_success")
                        : P2PTexts.c("button.p2p_host"));
                });
            })
            .bounds(this.width - 224, 8, 104, 20)
            .build());

        this.addRenderableWidget(Button.builder(P2PTexts.c("button.friend_multiplayer"), button ->
                this.minecraft.setScreen(new P2PConnectScreen((Screen) (Object) this)))
            .bounds(this.width - 112, 8, 104, 20)
            .build());
    }
}
