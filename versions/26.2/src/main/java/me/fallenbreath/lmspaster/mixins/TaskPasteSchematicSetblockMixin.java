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

import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicPerChunkCommand;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import me.fallenbreath.lmspaster.LitematicaServerPasterMod;
import me.fallenbreath.lmspaster.network.ClientNetworkHandler;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Queue;

@Mixin(TaskPasteSchematicPerChunkCommand.class)
public abstract class TaskPasteSchematicSetblockMixin
{
	@Shadow @Final protected Queue<String> queuedCommands;
	@Shadow @Final protected String setBlockCommand;
	@Shadow @Final protected String summonCommand;
	@Shadow @Final protected String delayCommand;
	@Shadow protected int maxCommandsPerTick;
	@Shadow protected int sentCommandsThisTick;
	@Shadow @Nullable protected IntBoundingBox currentBox;

	@Shadow
	protected abstract boolean shouldSetBlock(BlockState stateSchematic, BlockState stateClient);

	@Inject(method = "pasteBlock", at = @At("HEAD"), cancellable = true, remap = false)
	private void pasteBlockWithServerPaster(BlockPos pos, LevelChunk schematicChunk, ChunkAccess clientChunk, boolean ignoreLimit, CallbackInfo ci)
	{
		if (!ClientNetworkHandler.isServerPasterAvailable())
		{
			return;
		}

		BlockState stateSchematic = schematicChunk.getBlockState(pos);
		BlockState stateClient = clientChunk.getBlockState(pos);
		if (!this.shouldSetBlock(stateSchematic, stateClient))
		{
			ci.cancel();
			return;
		}

		BlockEntity blockEntity = schematicChunk.getBlockEntity(pos);
		if (blockEntity == null)
		{
			return;
		}

		CompoundTag tag = blockEntity.saveWithoutMetadata(schematicChunk.getLevel().registryAccess());
		tag.remove("id");
		tag.remove("x");
		tag.remove("y");
		tag.remove("z");

		String stateString = BlockStateParser.serialize(stateSchematic);
		String command = String.format(Locale.ROOT, "%s %d %d %d %s%s", this.setBlockCommand,
				pos.getX(), pos.getY(), pos.getZ(), stateString, tag);

		if (ClientNetworkHandler.canSendCommand(command))
		{
			LitematicaServerPasterMod.LOGGER.info("Pasting block {} at [{}, {}, {}] with nbt tag",
					stateSchematic.getBlock().getName().getString(), pos.getX(), pos.getY(), pos.getZ());
			this.queuedCommands.offer(command);
			ci.cancel();
		}
	}

	@Inject(method = "summonEntity", at = @At("HEAD"), cancellable = true, remap = false)
	private void summonEntityWithServerPaster(Entity entity, CallbackInfo ci)
	{
		if (!ClientNetworkHandler.isServerPasterAvailable())
		{
			return;
		}

		if (this.currentBox != null)
		{
			int x = entity.getBlockX();
			int y = entity.getBlockY();
			int z = entity.getBlockZ();
			if (x < this.currentBox.minX() || x > this.currentBox.maxX() ||
					y < this.currentBox.minY() || y > this.currentBox.maxY() ||
					z < this.currentBox.minZ() || z > this.currentBox.maxZ())
			{
				ci.cancel();
				return;
			}
		}

		if (entity.getVehicle() != null)
		{
			ci.cancel();
			return;
		}

		String id = EntityUtils.getEntityId(entity);
		if (id == null)
		{
			return;
		}

		CompoundTag tag = entity.saveWithoutId(new CompoundTag());
		tag.remove("UUIDMost");
		tag.remove("UUIDLeast");
		tag.remove("UUID");
		tag.remove("Pos");
		tag.remove("Dimension");

		String command = String.format(Locale.ROOT, "%s %s %f %f %f %s", this.summonCommand, id,
				entity.getX(), entity.getY(), entity.getZ(), tag);
		if (ClientNetworkHandler.canSendCommand(command))
		{
			LitematicaServerPasterMod.LOGGER.info("Summoning entity {} with nbt tag", entity.getType().getDescription().getString());
			this.queuedCommands.offer(command);
			ci.cancel();
		}
	}

	@Inject(method = "sendQueuedCommands", at = @At("HEAD"), cancellable = true, remap = false)
	private void sendQueuedCommandsWithServerPaster(CallbackInfo ci)
	{
		if (!ClientNetworkHandler.isServerPasterAvailable())
		{
			return;
		}

		while (this.sentCommandsThisTick < this.maxCommandsPerTick && !this.queuedCommands.isEmpty())
		{
			String command = this.queuedCommands.poll();
			if (command.equals(this.delayCommand))
			{
				this.sentCommandsThisTick = this.maxCommandsPerTick;
			}
			else
			{
				ClientNetworkHandler.sendCommand(command);
				++this.sentCommandsThisTick;
			}
		}

		ci.cancel();
	}
}
