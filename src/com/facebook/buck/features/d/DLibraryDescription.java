/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.features.d;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.rules.DescriptionWithTargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

public class DLibraryDescription
    implements DescriptionWithTargetGraph<DLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<DLibraryDescriptionArg>,
        VersionPropagator<DLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;

  public DLibraryDescription(
      ToolchainProvider toolchainProvider, DBuckConfig dBuckConfig, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<DLibraryDescriptionArg> getConstructorArgType() {
    return DLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      DLibraryDescriptionArg args) {
    BuildRuleResolver buildRuleResolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (buildTarget.getFlavors().contains(DDescriptionUtils.SOURCE_LINK_TREE)) {
      return DDescriptionUtils.createSourceSymlinkTree(
          buildTarget, projectFilesystem, pathResolver, ruleFinder, args.getSrcs());
    }

    BuildTarget sourceTreeTarget =
        buildTarget.withAppendedFlavors(DDescriptionUtils.SOURCE_LINK_TREE);
    DIncludes dIncludes =
        DIncludes.builder()
            .setLinkTree(DefaultBuildTargetSourcePath.of(sourceTreeTarget))
            .setSources(args.getSrcs().getPaths())
            .build();

    if (buildTarget.getFlavors().contains(CxxDescriptionEnhancer.STATIC_FLAVOR)) {
      buildRuleResolver.requireRule(sourceTreeTarget);
      return createStaticLibraryBuildRule(
          buildTarget,
          projectFilesystem,
          params,
          buildRuleResolver,
          pathResolver,
          ruleFinder,
          /* compilerFlags */ ImmutableList.of(),
          args.getSrcs(),
          dIncludes,
          PicType.PDC);
    }

    return new DLibrary(buildTarget, projectFilesystem, params, buildRuleResolver, dIncludes);
  }

  /** @return a BuildRule that creates a static library. */
  private BuildRule createStaticLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<String> compilerFlags,
      SourceList sources,
      DIncludes dIncludes,
      PicType pic) {

    CxxPlatform cxxPlatform = getCxxPlatform();

    ImmutableList<SourcePath> compiledSources =
        DDescriptionUtils.sourcePathsForCompiledSources(
            buildTarget,
            projectFilesystem,
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            dBuckConfig,
            compilerFlags,
            sources,
            dIncludes);

    // Write a build rule to create the archive for this library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            buildTarget, cxxPlatform.getFlavor(), pic);

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            projectFilesystem,
            buildTarget,
            cxxPlatform.getFlavor(),
            pic,
            Optional.empty(),
            cxxPlatform.getStaticLibraryExtension(),
            cxxBuckConfig.isUniqueLibraryNameEnabled());

    return Archive.from(
        staticTarget,
        projectFilesystem,
        ruleResolver,
        ruleFinder,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        staticLibraryPath,
        compiledSources,
        /* cacheable */ true);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      DLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(getCxxPlatform()));
  }

  private CxxPlatform getCxxPlatform() {
    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    return cxxPlatformsProviderFactory.getDefaultCxxPlatform();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractDLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourceList getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}
