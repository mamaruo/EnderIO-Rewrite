package com.enderio.conduits.common.network;

import com.enderio.api.conduit.ConduitTypes;
import com.enderio.api.conduit.IConduitType;
import com.enderio.conduits.common.blockentity.TieredConduit;
import com.enderio.conduits.common.init.EnderConduitTypes;
import com.enderio.core.common.blockentity.ColorControl;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.mojang.datafixers.util.Pair;
import dev.gigaherz.graph3.Graph;
import dev.gigaherz.graph3.GraphObject;
import dev.gigaherz.graph3.Mergeable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Mod.EventBusSubscriber
public class ConduitSavedData extends SavedData {

    ListMultimap<IConduitType, Graph<Mergeable.Dummy>> networks = ArrayListMultimap.create();

    public static ConduitSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ConduitSavedData::new, ConduitSavedData::new, "enderio:conduit_network");
    }

    private ConduitSavedData() {

    }

    private ConduitSavedData(CompoundTag nbt) {

    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        return nbt;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.START)
            return;
        if (event.level instanceof ServerLevel serverLevel) {
            for (var entry : get(serverLevel).networks.entries()) {
                //tick four times per second
                if (serverLevel.getGameTime() % 5 == ConduitTypes.getRegistry().getID(entry.getKey()) % 5) {

                    if (entry.getKey() instanceof TieredConduit tieredConduit && tieredConduit.getType().equals(new ResourceLocation("forge", "power"))) {
                        ListMultimap<ColorControl, ConnectorPos> inputs = ArrayListMultimap.create();
                        ListMultimap<ColorControl, ConnectorPos> outputs = ArrayListMultimap.create();
                        for (GraphObject<Mergeable.Dummy> object : entry.getValue().getObjects()) {
                            if (object instanceof NodeIdentifier nodeIdentifier) {
                                for (Direction direction: Direction.values()) {
                                    if (serverLevel.isLoaded(nodeIdentifier.getPos()) && serverLevel.shouldTickBlocksAt(nodeIdentifier.getPos())) {
                                        nodeIdentifier.getIOState(direction).ifPresent(ioState -> {
                                            ioState.in().ifPresent(color -> inputs.get(color).add(new ConnectorPos(nodeIdentifier.getPos(), direction)));
                                            ioState.out().ifPresent(color -> outputs.get(color).add(new ConnectorPos(nodeIdentifier.getPos(), direction)));
                                        });
                                    }
                                }
                            }
                        }
                        for (ColorControl color: inputs.keySet()) {
                            Map<ConnectorPos, IEnergyStorage> inputCaps = new HashMap<>();
                            for (ConnectorPos inputAt : inputs.get(color)) {
                                checkFor(serverLevel, inputAt, CapabilityEnergy.ENERGY).ifPresent(energy -> inputCaps.put(inputAt, energy));
                            }
                            if (inputCaps.isEmpty())
                                continue;
                            Map<ConnectorPos, IEnergyStorage> outputCaps = new HashMap<>();
                            for (ConnectorPos outputAt : outputs.get(color)) {
                                checkFor(serverLevel, outputAt, CapabilityEnergy.ENERGY).ifPresent(energy -> outputCaps.put(outputAt, energy));
                            }
                            for (Map.Entry<ConnectorPos, IEnergyStorage> inputEntry : inputCaps.entrySet()) {
                                int extracted = inputEntry.getValue().extractEnergy(tieredConduit.getTier(), true);
                                int inserted = 0;
                                for (Map.Entry<ConnectorPos, IEnergyStorage> outputEntry : outputCaps.entrySet()) {
                                    inserted += outputEntry.getValue().receiveEnergy(extracted - inserted, false);
                                    if (inserted == extracted)
                                        break;
                                }
                                inputEntry.getValue().extractEnergy(inserted, false);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void addPotentialGraph(IConduitType type, Graph<Mergeable.Dummy> graph, ServerLevel level) {
        get(level).addPotentialGraph(type, graph);
    }

    private void addPotentialGraph(IConduitType type, Graph<Mergeable.Dummy> graph) {
        if (!networks.get(type).contains(graph)) {
            networks.get(type).add(graph);
        }
    }

    private static <T> Optional<T> checkFor(ServerLevel level, ConnectorPos pos, Capability<T> cap) {
        BlockEntity blockEntity = level.getBlockEntity(pos.move());
        if (blockEntity != null)
            return blockEntity.getCapability(cap, pos.dir().getOpposite()).resolve();
        return Optional.empty();
    }

    private static record ConnectorPos(BlockPos pos, Direction dir) {
        private BlockPos move() {
            return pos.relative(dir);
        }
    }
}