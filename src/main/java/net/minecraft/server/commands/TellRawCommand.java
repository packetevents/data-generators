package net.minecraft.server.commands;
// Created by booky10 in PacketEventsGenerators (2:48 PM 24.11.2025)

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.booky.generation.GenerationMain;
import dev.booky.generation.util.GenerationUtil;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class TellRawCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ignoredBuildCtx) {
        dispatcher.register(Commands.literal("tellraw")
                .executes(ctx -> {
                    RegistryAccess registries = ctx.getSource().registryAccess();
                    GenerationUtil.VANILLA_REGISTRIES = registries;
                    GenerationUtil.VANILLA_REGISTRY_ACCESS = registries;
                    GenerationMain.main(new String[0]);
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
