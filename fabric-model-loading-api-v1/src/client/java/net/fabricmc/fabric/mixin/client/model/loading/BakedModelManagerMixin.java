/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.mixin.client.model.loading;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.class_10097;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.client.render.model.BlockStatesLoader;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.profiler.Profiler;

import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.fabricmc.fabric.impl.client.model.loading.ModelLoadingConstants;
import net.fabricmc.fabric.impl.client.model.loading.ModelLoadingEventDispatcher;
import net.fabricmc.fabric.impl.client.model.loading.ModelLoadingPluginManager;

@Mixin(BakedModelManager.class)
abstract class BakedModelManagerMixin implements FabricBakedModelManager {
	@Unique
	private volatile CompletableFuture<ModelLoadingEventDispatcher> eventDispatcherFuture;

	@Shadow
	private Map<ModelIdentifier, BakedModel> models;

	@Override
	public BakedModel getModel(Identifier id) {
		return models.get(ModelLoadingConstants.toResourceModelId(id));
	}

	@Inject(method = "reload", at = @At("HEAD"))
	private void onHeadReload(ResourceReloader.Synchronizer synchronizer, ResourceManager manager, Profiler prepareProfiler, Profiler applyProfiler, Executor prepareExecutor, Executor applyExecutor, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		eventDispatcherFuture = ModelLoadingPluginManager.preparePlugins(manager, prepareExecutor).thenApplyAsync(ModelLoadingEventDispatcher::new);
	}

	@ModifyReturnValue(method = "reload", at = @At("RETURN"))
	private CompletableFuture<Void> resetEventDispatcherFuture(CompletableFuture<Void> future) {
		return future.thenApplyAsync(v -> {
			eventDispatcherFuture = null;
			return v;
		});
	}

	@ModifyExpressionValue(method = "reload", at = @At(value = "INVOKE", target = "net/minecraft/client/render/model/BakedModelManager.reloadBlockStates(Lnet/minecraft/client/render/model/BlockStatesLoader;Lnet/minecraft/resource/ResourceManager;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
	private CompletableFuture<BlockStatesLoader.class_10095> hookBlockStateModelLoading(CompletableFuture<BlockStatesLoader.class_10095> modelsFuture) {
		CompletableFuture<BlockStatesLoader.class_10095> resolvedModelsFuture = eventDispatcherFuture.thenApplyAsync(ModelLoadingEventDispatcher::loadBlockStateModels);
		return modelsFuture.thenCombine(resolvedModelsFuture, (models, resolvedModels) -> {
			Map<ModelIdentifier, BlockStatesLoader.BlockModel> map = models.models();

			if (!(map instanceof HashMap)) {
				map = new HashMap<>(map);
				models = new BlockStatesLoader.class_10095(map);
			}

			map.putAll(resolvedModels.models());
			return models;
		});
	}

	@Redirect(
			method = "reload",
			at = @At(
					value = "INVOKE",
					target = "java/util/concurrent/CompletableFuture.thenCombineAsync(Ljava/util/concurrent/CompletionStage;Ljava/util/function/BiFunction;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
					ordinal = 0,
					remap = false
			))
	private CompletableFuture<class_10097> hookModelDiscovery(
			CompletableFuture<BlockStatesLoader.class_10095> self,
			CompletionStage<Map<Identifier, UnbakedModel>> otherFuture,
			BiFunction<BlockStatesLoader.class_10095, Map<Identifier, UnbakedModel>, class_10097> function,
			Executor executor) {
		CompletableFuture<Pair<BlockStatesLoader.class_10095, Map<Identifier, UnbakedModel>>> pairFuture = self.thenCombine(otherFuture, Pair::new);
		return pairFuture.thenCombineAsync(eventDispatcherFuture, (pair, eventDispatcher) -> {
			ModelLoadingEventDispatcher.CURRENT.set(eventDispatcher);
			class_10097 class_10097 = function.apply(pair.getLeft(), pair.getRight());
			ModelLoadingEventDispatcher.CURRENT.remove();
			return class_10097;
		}, executor);
	}

	@ModifyArg(method = "reload", at = @At(value = "INVOKE", target = "java/util/concurrent/CompletableFuture.thenApplyAsync (Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", ordinal = 1), index = 0)
	private Function<Void, Object> hookModelBaking(Function<Void, Object> function) {
		return v -> {
			ModelLoadingEventDispatcher.CURRENT.set(eventDispatcherFuture.join());
			Object bakingResult = function.apply(v);
			ModelLoadingEventDispatcher.CURRENT.remove();
			return bakingResult;
		};
	}
}
