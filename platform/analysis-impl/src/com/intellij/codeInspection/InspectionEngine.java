/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.intellij.codeInspection;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.impl.Divider;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.codeInspection.reference.RefVisitor;
import com.intellij.concurrency.JobLauncher;
import com.intellij.lang.Language;
import com.intellij.lang.MetaLanguage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveVisitor;
import com.intellij.util.CommonProcessors;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.SmartHashSet;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class InspectionEngine {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.InspectionEngine");
  private static final Set<Class<? extends LocalInspectionTool>> RECURSIVE_VISITOR_TOOL_CLASSES = ContainerUtil.newConcurrentSet();

  @NotNull
  public static PsiElementVisitor createVisitorAndAcceptElements(@NotNull LocalInspectionTool tool,
                                                                 @NotNull ProblemsHolder holder,
                                                                 boolean isOnTheFly,
                                                                 @NotNull LocalInspectionToolSession session,
                                                                 @NotNull List<? extends PsiElement> elements,
                                                                 @NotNull Set<String> elementDialectIds,
                                                                 @Nullable("null means all accepted") Set<String> dialectIdsSpecifiedForTool) {
    PsiElementVisitor visitor = tool.buildVisitor(holder, isOnTheFly, session);
    //noinspection ConstantConditions
    if (visitor == null) {
      LOG.error("Tool " + tool + " (" + tool.getClass()+ ") must not return null from the buildVisitor() method");
    }
    else if (visitor instanceof PsiRecursiveVisitor && RECURSIVE_VISITOR_TOOL_CLASSES.add(tool.getClass())) {
      LOG.error("The visitor returned from LocalInspectionTool.buildVisitor() must not be recursive: " + tool);
    }

    tool.inspectionStarted(session, isOnTheFly);
    acceptElements(elements, visitor, elementDialectIds, dialectIdsSpecifiedForTool);
    return visitor;
  }

  public static void acceptElements(@NotNull List<? extends PsiElement> elements,
                                    @NotNull PsiElementVisitor elementVisitor,
                                    @NotNull Set<String> elementDialectIds,
                                    @Nullable("null means all accepted") Set<String> dialectIdsSpecifiedForTool) {
    if (dialectIdsSpecifiedForTool != null && !intersect(elementDialectIds, dialectIdsSpecifiedForTool)) return;
    //noinspection ForLoopReplaceableByForEach
    for (int i = 0, elementsSize = elements.size(); i < elementsSize; i++) {
      PsiElement element = elements.get(i);
      element.accept(elementVisitor);
      ProgressManager.checkCanceled();
    }
  }

  private static boolean intersect(@NotNull Set<String> ids1, @NotNull Set<String> ids2) {
    if (ids1.size() > ids2.size()) {
      Set<String> tmp = ids1;
      ids1 = ids2;
      ids2 = tmp;
    }
    for (String id : ids1) {
      if (ids2.contains(id)) return true;
    }
    return false;
  }

  @NotNull
  private static List<ProblemDescriptor> inspect(@NotNull final List<? extends LocalInspectionToolWrapper> toolWrappers,
                                                 @NotNull final PsiFile file,
                                                 @NotNull final InspectionManager iManager,
                                                 @NotNull final ProgressIndicator indicator) {
    final Map<String, List<ProblemDescriptor>> problemDescriptors = inspectEx(toolWrappers, file, iManager, false, indicator);

    final List<ProblemDescriptor> result = new ArrayList<>();
    for (List<ProblemDescriptor> group : problemDescriptors.values()) {
      result.addAll(group);
    }
    return result;
  }

  // public for Upsource
  // returns map (toolName -> problem descriptors)
  @NotNull
  public static Map<String, List<ProblemDescriptor>> inspectEx(@NotNull final List<? extends LocalInspectionToolWrapper> toolWrappers,
                                                               @NotNull final PsiFile file,
                                                               @NotNull final InspectionManager iManager,
                                                               final boolean isOnTheFly,
                                                               @NotNull final ProgressIndicator indicator) {
    if (toolWrappers.isEmpty()) return Collections.emptyMap();

    TextRange range = file.getTextRange();
    List<Divider.DividedElements> allDivided = new ArrayList<>();
    Divider.divideInsideAndOutsideAllRoots(file, range, range, Conditions.alwaysTrue(), new CommonProcessors.CollectProcessor<>(allDivided));

    List<PsiElement> elements = ContainerUtil.concat(
      (List<List<PsiElement>>)ContainerUtil.map(allDivided, d -> ContainerUtil.concat(d.inside, d.outside, d.parents)));

    return inspectElements(toolWrappers, file, iManager, isOnTheFly, indicator, elements,
                           calcElementDialectIds(elements));
  }

  // returns map tool.shortName -> list of descriptors found
  @NotNull
  static Map<String, List<ProblemDescriptor>> inspectElements(@NotNull List<? extends LocalInspectionToolWrapper> toolWrappers,
                                                              @NotNull final PsiFile file,
                                                              @NotNull final InspectionManager iManager,
                                                              final boolean isOnTheFly,
                                                              @NotNull ProgressIndicator indicator,
                                                              @NotNull final List<? extends PsiElement> elements,
                                                              @NotNull final Set<String> elementDialectIds) {
    TextRange range = file.getTextRange();
    final LocalInspectionToolSession session = new LocalInspectionToolSession(file, range.getStartOffset(), range.getEndOffset());

    Map<LocalInspectionToolWrapper, Set<String>> toolToSpecifiedDialectIds = getToolsToSpecifiedLanguages(toolWrappers);
    List<Entry<LocalInspectionToolWrapper, Set<String>>> entries = new ArrayList<>(toolToSpecifiedDialectIds.entrySet());
    final Map<String, List<ProblemDescriptor>> resultDescriptors = new ConcurrentHashMap<>();
    Processor<Entry<LocalInspectionToolWrapper, Set<String>>> processor = entry -> {
      ProblemsHolder holder = new ProblemsHolder(iManager, file, isOnTheFly);
      final LocalInspectionTool tool = entry.getKey().getTool();
      Set<String> dialectIdsSpecifiedForTool = entry.getValue();
      createVisitorAndAcceptElements(tool, holder, isOnTheFly, session, elements, elementDialectIds, dialectIdsSpecifiedForTool);

      tool.inspectionFinished(session, holder);

      if (holder.hasResults()) {
        resultDescriptors.put(tool.getShortName(), ContainerUtil.filter(holder.getResults(), descriptor -> {
          PsiElement element = descriptor.getPsiElement();
          return element == null || !SuppressionUtil.inspectionResultSuppressed(element, tool);
        }));
      }

      return true;
    };
    JobLauncher.getInstance().invokeConcurrentlyUnderProgress(entries, indicator, processor);

    return resultDescriptors;
  }

  @NotNull
  public static List<ProblemDescriptor> runInspectionOnFile(@NotNull final PsiFile file,
                                                            @NotNull InspectionToolWrapper toolWrapper,
                                                            @NotNull final GlobalInspectionContext inspectionContext) {
    final InspectionManager inspectionManager = InspectionManager.getInstance(file.getProject());
    toolWrapper.initialize(inspectionContext);
    RefManagerImpl refManager = (RefManagerImpl)inspectionContext.getRefManager();
    refManager.inspectionReadActionStarted();
    try {
      if (toolWrapper instanceof LocalInspectionToolWrapper) {
        return inspect(Collections.singletonList((LocalInspectionToolWrapper)toolWrapper), file, inspectionManager, new EmptyProgressIndicator());
      }
      if (toolWrapper instanceof GlobalInspectionToolWrapper) {
        final GlobalInspectionTool globalTool = ((GlobalInspectionToolWrapper)toolWrapper).getTool();
        final List<ProblemDescriptor> descriptors = new ArrayList<>();
        if (globalTool instanceof GlobalSimpleInspectionTool) {
          GlobalSimpleInspectionTool simpleTool = (GlobalSimpleInspectionTool)globalTool;
          ProblemsHolder problemsHolder = new ProblemsHolder(inspectionManager, file, false);
          ProblemDescriptionsProcessor collectProcessor = new ProblemDescriptionsProcessor() {
            @Override
            public CommonProblemDescriptor[] getDescriptions(@NotNull RefEntity refEntity) {
              return descriptors.toArray(CommonProblemDescriptor.EMPTY_ARRAY);
            }

            @Override
            public void ignoreElement(@NotNull RefEntity refEntity) {
              throw new UnsupportedOperationException();
            }

            @Override
            public void resolveProblem(@NotNull CommonProblemDescriptor descriptor) {
              throw new UnsupportedOperationException();
            }

            @Override
            public void addProblemElement(@Nullable RefEntity refEntity, @NotNull CommonProblemDescriptor... commonProblemDescriptors) {
              if (!(refEntity instanceof RefElement)) return;
              PsiElement element = ((RefElement)refEntity).getElement();
              convertToProblemDescriptors(element, commonProblemDescriptors, descriptors);
            }

            @Override
            public RefEntity getElement(@NotNull CommonProblemDescriptor descriptor) {
              throw new RuntimeException();
            }
          };
          simpleTool.checkFile(file, inspectionManager, problemsHolder, inspectionContext, collectProcessor);
          return descriptors;
        }
        RefElement fileRef = refManager.getReference(file);
        final AnalysisScope scope = new AnalysisScope(file);
        assert fileRef != null;
        fileRef.accept(new RefVisitor(){
          @Override
          public void visitElement(@NotNull RefEntity elem) {
            CommonProblemDescriptor[] elemDescriptors = globalTool.checkElement(elem, scope, inspectionManager, inspectionContext);
            if (elemDescriptors != null) {
              convertToProblemDescriptors(file, elemDescriptors, descriptors);
            }

            for (RefEntity child : elem.getChildren()) {
              child.accept(this);
            }
          }
        });
        return descriptors;
      }
    }
    finally {
      refManager.inspectionReadActionFinished();
      toolWrapper.cleanup(file.getProject());
      inspectionContext.cleanup();
    }
    return Collections.emptyList();
  }

  private static void convertToProblemDescriptors(@NotNull PsiElement element,
                                                  @NotNull CommonProblemDescriptor[] commonProblemDescriptors,
                                                  @NotNull List<? super ProblemDescriptor> descriptors) {
    for (CommonProblemDescriptor common : commonProblemDescriptors) {
      if (common instanceof ProblemDescriptor) {
        descriptors.add((ProblemDescriptor)common);
      }
      else {
        ProblemDescriptorBase base =
          new ProblemDescriptorBase(element, element, common.getDescriptionTemplate(), (LocalQuickFix[])common.getFixes(),
                                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false, null, false, false);
        descriptors.add(base);
      }
    }
  }

  // returns map tool -> set of languages and dialects for that tool specified in plugin.xml
  @NotNull
  public static Map<LocalInspectionToolWrapper, Set<String>> getToolsToSpecifiedLanguages(@NotNull List<? extends LocalInspectionToolWrapper> toolWrappers) {
    Map<LocalInspectionToolWrapper, Set<String>> toolToLanguages = new THashMap<>();
    for (LocalInspectionToolWrapper wrapper : toolWrappers) {
      ProgressManager.checkCanceled();
      Set<String> specifiedLangIds = getDialectIdsSpecifiedForTool(wrapper);
      toolToLanguages.put(wrapper, specifiedLangIds);
    }
    return toolToLanguages;
  }

  @Nullable("null means not specified")
  public static Set<String> getDialectIdsSpecifiedForTool(@NotNull LocalInspectionToolWrapper wrapper) {
    String langId = wrapper.getLanguage();
    if (langId == null) {
      return null;
    }
    Language language = Language.findLanguageByID(langId);
    Set<String> result;
    if (language == null) {
      // unknown language in plugin.xml, ignore
      result = Collections.singleton(langId);
    }
    else if (language instanceof MetaLanguage) {
      Collection<Language> matchingLanguages = ((MetaLanguage) language).getMatchingLanguages();
      result = new THashSet<>();
      for (Language matchingLanguage : matchingLanguages) {
        result.addAll(getLanguageWithDialects(wrapper, matchingLanguage));
      }
    }
    else {
      result = getLanguageWithDialects(wrapper, language);
    }
    return result;
  }

  @NotNull
  private static Set<String> getLanguageWithDialects(@NotNull LocalInspectionToolWrapper wrapper, Language language) {
    List<Language> dialects = language.getDialects();
    boolean applyToDialects = wrapper.applyToDialects();
    Set<String> result = applyToDialects && !dialects.isEmpty() ? new THashSet<>(1 + dialects.size()) : new SmartHashSet<>();
    result.add(language.getID());
    if (applyToDialects) {
      for (Language dialect : dialects) {
        result.add(dialect.getID());
      }
    }
    return result;
  }

  @NotNull
  public static Set<String> calcElementDialectIds(@NotNull List<? extends PsiElement> inside, @NotNull List<? extends PsiElement> outside) {
    Set<String> dialectIds = new SmartHashSet<>();
    Set<Language> processedLanguages = new SmartHashSet<>();
    addDialects(inside, processedLanguages, dialectIds);
    addDialects(outside, processedLanguages, dialectIds);
    return dialectIds;
  }

  @NotNull
  public static Set<String> calcElementDialectIds(@NotNull List<? extends PsiElement> elements) {
    Set<String> dialectIds = new SmartHashSet<>();
    Set<Language> processedLanguages = new SmartHashSet<>();
    addDialects(elements, processedLanguages, dialectIds);
    return dialectIds;
  }

  private static void addDialects(@NotNull List<? extends PsiElement> elements,
                                  @NotNull Set<? super Language> outProcessedLanguages,
                                  @NotNull Set<? super String> outDialectIds) {
    for (PsiElement element : elements) {
      Language language = element.getLanguage();
      outDialectIds.add(language.getID());
      if (outProcessedLanguages.add(language)) {
        for (Language dialect : language.getDialects()) {
          outDialectIds.add(dialect.getID());
        }
      }
    }
  }
}
