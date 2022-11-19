package gregtech.core;

import crafttweaker.CraftTweakerAPI;
import gregtech.apiOld.GTValues;
import gregtech.apiOld.GregTechAPI;
import gregtech.apiOld.block.IHeatingCoilBlockStats;
import gregtech.core.capability.SimpleCapabilityManager;
import gregtech.apiOld.cover.CoverBehaviorUIFactory;
import gregtech.apiOld.cover.CoverDefinition;
import gregtech.apiOld.fluids.MetaFluids;
import gregtech.apiOld.gui.UIFactory;
import gregtech.apiOld.items.gui.PlayerInventoryUIFactory;
import gregtech.apiOld.metatileentity.MetaTileEntityUIFactory;
import gregtech.api.modules.GregTechModule;
import gregtech.api.modules.IGregTechModule;
import gregtech.apiOld.recipes.RecipeMap;
import gregtech.apiOld.recipes.recipeproperties.TemperatureProperty;
import gregtech.apiOld.sound.GTSounds;
import gregtech.apiOld.unification.OreDictUnifier;
import gregtech.apiOld.unification.material.Materials;
import gregtech.apiOld.util.CapesRegistry;
import gregtech.apiOld.util.GTLog;
import gregtech.apiOld.util.NBTUtil;
import gregtech.apiOld.util.VirtualTankRegistry;
import gregtech.apiOld.util.input.KeyBind;
import gregtech.apiOld.worldgen.bedrockFluids.BedrockFluidVeinHandler;
import gregtech.apiOld.worldgen.bedrockFluids.BedrockFluidVeinSaveData;
import gregtech.apiOld.worldgen.config.WorldGenRegistry;
import gregtech.common.CommonProxy;
import gregtech.common.ConfigHolder;
import gregtech.common.MetaEntities;
import gregtech.common.blocks.BlockWireCoil;
import gregtech.common.blocks.MetaBlocks;
import gregtech.common.command.CommandHand;
import gregtech.common.command.CommandRecipeCheck;
import gregtech.common.command.CommandShaders;
import gregtech.common.command.worldgen.CommandWorldgen;
import gregtech.common.covers.CoverBehaviors;
import gregtech.common.covers.filter.FilterTypeRegistry;
import gregtech.common.items.MetaItems;
import gregtech.common.metatileentities.MetaTileEntities;
import gregtech.common.worldgen.LootTableHelper;
import gregtech.core.network.packets.*;
import gregtech.integration.GroovyScriptCompat;
import gregtech.loaders.dungeon.DungeonLootLoader;
import gregtech.modules.GregTechModules;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraftforge.classloading.FMLForgePlugin;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.Map;

import static gregtech.apiOld.GregTechAPI.*;
import static gregtech.apiOld.GregTechAPI.HEATING_COILS;

@GregTechModule(
        moduleID       = GregTechModules.MODULE_CORE,
        containerID    = GTValues.MODID,
        name           = "GregTech Core",
        descriptionKey = "gregtech.module.core.description",
        coreModule     = true
)
/**
 * CURRENT STATE:
 * - Currently this module is all of GregTech, loaded via a module instead of directly by the @Mod annotated class.
 * - This will need to be blown apart, but this is a first basic test of functionality, as everything should still
 *   work fully in this state.
 */
public class CoreModule implements IGregTechModule {

    // todo remove this
    @SidedProxy(modId = GTValues.MODID, clientSide = "gregtech.client.ClientProxy", serverSide = "gregtech.common.CommonProxy")
    public static CommonProxy proxy;

    public static final Logger logger = LogManager.getLogger("GregTech Core");

    @Nonnull
    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        /* init GroovyScript compat */
        GroovyScriptCompat.init();

        /* Start UI Factory Registration */
        UI_FACTORY_REGISTRY.unfreeze();
        GTLog.logger.info("Registering GTCEu UI Factories");
        MetaTileEntityUIFactory.INSTANCE.init();
        PlayerInventoryUIFactory.INSTANCE.init();
        CoverBehaviorUIFactory.INSTANCE.init();
        GTLog.logger.info("Registering addon UI Factories");
        MinecraftForge.EVENT_BUS.post(new GregTechAPI.RegisterEvent<>(UI_FACTORY_REGISTRY, UIFactory.class));
        UI_FACTORY_REGISTRY.freeze();
        /* End UI Factory Registration */

        SimpleCapabilityManager.init();

        /* Start Material Registration */

        // First, register CEu Materials
        MATERIAL_REGISTRY.unfreeze();
        GTLog.logger.info("Registering GTCEu Materials");
        Materials.register();

        // Then, register addon Materials
        GTLog.logger.info("Registering addon Materials");
        MinecraftForge.EVENT_BUS.post(new GregTechAPI.MaterialEvent());

        // Then, run CraftTweaker Material registration scripts
        if (Loader.isModLoaded(GTValues.MODID_CT)) {
            GTLog.logger.info("Running early CraftTweaker initialization scripts...");
            runEarlyCraftTweakerScripts();
        }

        // Fire Post-Material event, intended for when Materials need to be iterated over in-full before freezing
        // Block entirely new Materials from being added in the Post event
        MATERIAL_REGISTRY.closeRegistry();
        MinecraftForge.EVENT_BUS.post(new GregTechAPI.PostMaterialEvent());

        // Freeze Material Registry before processing Items, Blocks, and Fluids
        MATERIAL_REGISTRY.freeze();
        /* End Material Registration */

        OreDictUnifier.init();
        NBTUtil.registerSerializers();

        MetaBlocks.init();
        MetaItems.init();
        MetaFluids.init();

        GTSounds.registerSounds();

        /* Start MetaTileEntity Registration */
        MTE_REGISTRY.unfreeze();
        GTLog.logger.info("Registering GTCEu Meta Tile Entities");
        MetaTileEntities.init();
        /* End CEu MetaTileEntity Registration */
        /* Addons not done via an Event due to how much must be initialized for MTEs to register */

        MetaEntities.init();

        /* Start Heating Coil Registration */
        for (BlockWireCoil.CoilType type : BlockWireCoil.CoilType.values()) {
            HEATING_COILS.put(MetaBlocks.WIRE_COIL.getState(type), type);
        }
        /* End Heating Coil Registration */

        proxy.onPreLoad();
        KeyBind.init();
    }

    @Override
    public void registerPackets() {
        GregTechAPI.networkHandler.registerPacket(PacketUIOpen.class);
        GregTechAPI.networkHandler.registerPacket(PacketUIWidgetUpdate.class);
        GregTechAPI.networkHandler.registerPacket(PacketUIClientAction.class);
        GregTechAPI.networkHandler.registerPacket(PacketBlockParticle.class);
        GregTechAPI.networkHandler.registerPacket(PacketClipboard.class);
        GregTechAPI.networkHandler.registerPacket(PacketClipboardUIWidgetUpdate.class);
        GregTechAPI.networkHandler.registerPacket(PacketPluginSynced.class);
        GregTechAPI.networkHandler.registerPacket(PacketRecoverMTE.class);
        GregTechAPI.networkHandler.registerPacket(PacketKeysPressed.class);
        GregTechAPI.networkHandler.registerPacket(PacketFluidVeinList.class);
        GregTechAPI.networkHandler.registerPacket(PacketNotifyCapeChange.class);
        GregTechAPI.networkHandler.registerPacket(PacketReloadShaders.class);
        GregTechAPI.networkHandler.registerPacket(PacketClipboardNBTUpdate.class);
    }

    @Optional.Method(modid = GTValues.MODID_CT)
    private void runEarlyCraftTweakerScripts() {
        CraftTweakerAPI.tweaker.loadScript(false, "gregtech");
    }

    @Override
    public void init(FMLInitializationEvent event) {
        MTE_REGISTRY.freeze(); // freeze once addon preInit is finished
        proxy.onLoad();
        if (RecipeMap.isFoundInvalidRecipe()) {
            GTLog.logger.fatal("Seems like invalid recipe was found.");
            //crash if config setting is set to false, or we are in deobfuscated environment
            if (!ConfigHolder.misc.ignoreErrorOrInvalidRecipes || !FMLForgePlugin.RUNTIME_DEOBF) {
                GTLog.logger.fatal("Loading cannot continue. Either fix or report invalid recipes, or enable ignoreErrorOrInvalidRecipes in the config as a temporary solution");
                throw new LoaderException("Found at least one invalid recipe. Please read the log above for more details.");
            } else {
                GTLog.logger.fatal("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
                GTLog.logger.fatal("Ignoring invalid recipes and continuing loading");
                GTLog.logger.fatal("Some things may lack recipes or have invalid ones, proceed at your own risk");
                GTLog.logger.fatal("Report to GTCEu GitHub to get more help and fix the problem");
                GTLog.logger.fatal("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
            }
        }

        WorldGenRegistry.INSTANCE.initializeRegistry();

        LootTableHelper.initialize();
        FilterTypeRegistry.init();

        /* Start Cover Definition Registration */
        COVER_REGISTRY.unfreeze();
        CoverBehaviors.init();
        MinecraftForge.EVENT_BUS.post(new RegisterEvent<>(COVER_REGISTRY, CoverDefinition.class));
        COVER_REGISTRY.freeze();
        /* End Cover Definition Registration */

        DungeonLootLoader.init();
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        proxy.onPostLoad();
        BedrockFluidVeinHandler.recalculateChances(true);
        // registers coil types for the BlastTemperatureProperty used in Blast Furnace Recipes
        // runs AFTER craftTweaker
        for (Map.Entry<IBlockState, IHeatingCoilBlockStats> entry : GregTechAPI.HEATING_COILS.entrySet()) {
            IHeatingCoilBlockStats value = entry.getValue();
            if (value != null) {
                String name = entry.getKey().getBlock().getTranslationKey();
                if (!name.endsWith(".name")) name = String.format("%s.name", name);
                TemperatureProperty.registerCoilType(value.getCoilTemperature(), value.getMaterial(), name);
            }
        }
    }

    @Override
    public void loadComplete(FMLLoadCompleteEvent event) {
        proxy.onLoadComplete();
    }

    @Override
    public void serverStarting(FMLServerStartingEvent event) {
        GregTechAPI.commandManager.addCommand(new CommandWorldgen());
        GregTechAPI.commandManager.addCommand(new CommandHand());
        GregTechAPI.commandManager.addCommand(new CommandRecipeCheck());
        GregTechAPI.commandManager.addCommand(new CommandShaders());
        CapesRegistry.load();
    }

    @Override
    public void serverStarted(FMLServerStartedEvent event) {
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.SERVER) {
            World world = FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld();
            if (!world.isRemote) {
                BedrockFluidVeinSaveData saveData = (BedrockFluidVeinSaveData) world.loadData(BedrockFluidVeinSaveData.class, BedrockFluidVeinSaveData.dataName);
                if (saveData == null) {
                    saveData = new BedrockFluidVeinSaveData(BedrockFluidVeinSaveData.dataName);
                    world.setData(BedrockFluidVeinSaveData.dataName, saveData);
                }
                BedrockFluidVeinSaveData.setInstance(saveData);
            }
        }
    }

    @Override
    public void serverStopped(FMLServerStoppedEvent event) {
        VirtualTankRegistry.clearMaps();
        CapesRegistry.clearMaps();
    }
}
