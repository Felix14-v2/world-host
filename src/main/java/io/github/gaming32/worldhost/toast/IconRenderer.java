package io.github.gaming32.worldhost.toast;

import org.jetbrains.annotations.NotNull;

//#if MC >= 1_20_00
import net.minecraft.client.gui.GuiGraphics;
//#else
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//#endif

@FunctionalInterface
public interface IconRenderer {
    void draw(
        @NotNull
        //#if MC < 1_20_00
        //$$ PoseStack context,
        //#else
        GuiGraphics context,
        //#endif
        int x, int y, int width, int height
    );
}
