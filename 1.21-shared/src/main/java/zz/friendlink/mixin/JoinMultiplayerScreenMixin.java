package zz.friendlink.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zz.friendlink.gui.P2PConnectScreen;
import zz.friendlink.i18n.P2PTexts;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void friendLink$addButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(P2PTexts.c("button.friend_multiplayer"), button ->
                this.minecraft.setScreen(new P2PConnectScreen((Screen) (Object) this)))
            .bounds(this.width - 112, 8, 104, 20)
            .build());
    }
}
