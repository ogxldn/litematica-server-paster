/*
 * This file is part of the Litematica Server Paster project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2026  Fallen_Breath and contributors
 *
 * Litematica Server Paster is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Litematica Server Paster is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Litematica Server Paster.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.fallenbreath.lmspaster.mixins;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicPerChunkBase;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicPerChunkCommand;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PasteNbtBehavior;
import fi.dy.masa.malilib.util.position.LayerRange;
import me.fallenbreath.lmspaster.LitematicaServerPasterMod;
import me.fallenbreath.lmspaster.network.ClientNetworkHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.function.Consumer;

@Mixin(TaskPasteSchematicPerChunkCommand.class)
public abstract class TaskPasteSchematicSetblockMixin extends TaskPasteSchematicPerChunkBase
{
	@Shadow(remap = false) @Final protected String setBlockCommand;

	@Unique
	private ChunkAccess currentSchematicChunk;

	public TaskPasteSchematicSetblockMixin(Collection<SchematicPlacement> placements, LayerRange range, boolean changedBlocksOnly)
	{
		super(placements, range, changedBlocksOnly);
	}

	@Unique
	private static final String CUSTOM_COMMAND_PREFIX = String.format("##%s##", LitematicaServerPasterMod.MOD_ID);

	@ModifyVariable(
			method = "generateFillVolumes",
			at = @At(
					value = "INVOKE",
					target = "Lit/unimi/dsi/fastutil/longs/LongArrayList;clear()V"
			),
			ordinal = 0,
			remap = false
	)
	private boolean forcedSetIgnoreBeFromFill(boolean ignoreBeFromFill)
	{
		return ignoreBeFromFill || ClientNetworkHandler.isServerPasterAvailable();
	}

	@Override
	protected void sendCommand(String command, LocalPlayer player)
	{
		if (command.startsWith(CUSTOM_COMMAND_PREFIX))
		{
			command = command.substring(CUSTOM_COMMAND_PREFIX.length());
			if (command.charAt(0) != '/')
			{
				command = '/' + command;
			}
			ClientNetworkHandler.sendCommand(command);
		}
		else
		{
			super.sendCommand(command, player);
		}
	}

	@Unique
	@Nullable
	private String customCommand = null;

	@Inject(method = "pasteBlock", at = @At("HEAD"), remap = false)
	private void recordCurrentSchematicChunk(BlockPos pos, LevelChunk schematicChunk, ChunkAccess clientChunk, boolean ignoreLimit, CallbackInfo ci)
	{
		this.currentSchematicChunk = schematicChunk;
	}

	@Inject(
			method = "queueSetBlockCommand(IIILnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Consumer;)V",
			slice = @Slice(
					from = @At(
							value = "FIELD",
							target = "Lfi/dy/masa/litematica/scheduler/tasks/TaskPasteSchematicPerChunkCommand;setBlockCommand:Ljava/lang/String;",
							remap = false
					)
			),
			at = @At(
					value = "INVOKE",
					target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",
					remap = false,
					ordinal = 0
			),
			remap = false
	)
	private void useCustomLongChatPacketToPasteBlockNbtDirectly(int x, int y, int z, BlockState state, Consumer<String> commandHandler, CallbackInfo ci)
	{
		if (Configs.Generic.PASTE_NBT_BEHAVIOR.getOptionListValue() != PasteNbtBehavior.NONE)
		{
			return;
		}
		if (ClientNetworkHandler.isServerPasterAvailable())
		{
			BlockEntity blockEntity = this.currentSchematicChunk.getBlockEntity(new BlockPos(x, y, z));
			if (blockEntity != null)
			{
				String stateString = BlockStateParser.serialize(state);
				CompoundTag tag = blockEntity.saveWithoutMetadata(this.world.registryAccess());
				tag.remove("id");
				tag.remove("x");
				tag.remove("y");
				tag.remove("z");
				String command = String.format("/%s %s %s %s %s%s", this.setBlockCommand, x, y, z, stateString, tag);
				if (ClientNetworkHandler.canSendCommand(command))
				{
					LitematicaServerPasterMod.LOGGER.info("Pasting block {} at [{}, {}, {}] with nbt tag", state.getBlock().getName().getString(), x, y, z);
					this.customCommand = command;
				}
			}
		}
	}

	@ModifyArg(
			method = "queueSetBlockCommand(IIILnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Consumer;)V",
			slice = @Slice(
					from = @At(
							value = "FIELD",
							target = "Lfi/dy/masa/litematica/scheduler/tasks/TaskPasteSchematicPerChunkCommand;setBlockCommand:Ljava/lang/String;",
							remap = false
					)
			),
			at = @At(
					value = "INVOKE",
					target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",
					ordinal = 0,
					remap = false
			),
			remap = false
	)
	private Object useCustomLongChatPacketToPasteBlockNbtDirectly_setBlock(Object obj)
	{
		return this.useCustomLongChatIfPossible(obj);
	}

	@ModifyArg(
			method = "summonEntity",
			at = @At(
					value = "INVOKE",
					target = "Ljava/util/Queue;offer(Ljava/lang/Object;)Z",
					ordinal = 0,
					remap = false
			),
			remap = false
	)
	private Object useCustomLongChatPacketToPasteEntityNbtDirectly(Object obj)
	{
		return this.useCustomLongChatIfPossible(obj);
	}

	@Unique
	private Object useCustomLongChatIfPossible(Object obj)
	{
		if (this.customCommand != null && obj instanceof String)
		{
			obj = CUSTOM_COMMAND_PREFIX + this.customCommand;
			this.customCommand = null;
		}
		return obj;
	}

	@Unique
	private Entity currentEntity;
	@Unique
	private boolean cancelThisEntityPaste;

	@Inject(method = "summonEntity", at = @At("HEAD"), remap = false)
	private void recordCurrentEntity(Entity entity, CallbackInfo ci)
	{
		this.currentEntity = entity;
	}

	@ModifyVariable(
			method = "summonEntity",
			at = @At(
					value = "STORE",
					target = "Ljava/lang/String;format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
					ordinal = 0,
					remap = false
			),
			ordinal = 1,
			remap = false
	)
	private String useCustomLongChatPacketToPasteEntityNbtDirectly(String baseCommand)
	{
		this.cancelThisEntityPaste = false;
		if (ClientNetworkHandler.isServerPasterAvailable() && this.currentEntity != null)
		{
			if (this.currentEntity.getVehicle() != null)
			{
				this.cancelThisEntityPaste = true;
				return baseCommand;
			}

			CompoundTag tag;
			try (ProblemReporter.ScopedCollector logging = new ProblemReporter.ScopedCollector(this.currentEntity.problemPath(), LitematicaServerPasterMod.LOGGER))
			{
				TagValueOutput view = TagValueOutput.createWithContext(logging, this.currentEntity.registryAccess());
				this.currentEntity.save(view);
				tag = view.buildResult();
			}

			tag.remove("UUID");
			tag.remove("Pos");
			tag.remove("Dimension");

			String tagString = NbtUtils.toPrettyComponent(tag).getString();
			String command = baseCommand + " " + tagString;
			if (ClientNetworkHandler.canSendCommand(command))
			{
				LitematicaServerPasterMod.LOGGER.info("Summoning entity {} with nbt tag", this.currentEntity.getType().getDescription().getString());
				this.customCommand = command;
			}
		}
		return baseCommand;
	}

	@Inject(
			method = "summonEntity",
			at = @At(
					value = "INVOKE",
					target = "Ljava/util/Queue;offer(Ljava/lang/Object;)Z",
					remap = false
			),
			remap = false,
			cancellable = true
	)
	private void cancelNullCommand(Entity entity, CallbackInfo ci)
	{
		if (this.cancelThisEntityPaste)
		{
			ci.cancel();
		}
	}
}
