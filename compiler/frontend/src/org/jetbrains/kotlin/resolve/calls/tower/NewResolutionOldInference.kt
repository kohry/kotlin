/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.synthetic.SyntheticMemberDescriptor
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DeprecationResolver
import org.jetbrains.kotlin.resolve.TemporaryBindingTrace
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.calls.CallTransformer
import org.jetbrains.kotlin.resolve.calls.CandidateResolver
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.isBinaryRemOperator
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.isConventionCall
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.isInfixCall
import org.jetbrains.kotlin.resolve.calls.callUtil.createLookupLocation
import org.jetbrains.kotlin.resolve.calls.context.*
import org.jetbrains.kotlin.resolve.calls.inference.CoroutineInferenceSupport
import org.jetbrains.kotlin.resolve.calls.inference.returnTypeOrNothing
import org.jetbrains.kotlin.resolve.calls.model.KotlinCallKind
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.results.OverloadResolutionResultsImpl
import org.jetbrains.kotlin.resolve.calls.results.ResolutionResultsHandler
import org.jetbrains.kotlin.resolve.calls.results.ResolutionStatus
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.calls.tasks.*
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.descriptorUtil.hasDynamicExtensionAnnotation
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.SyntheticScopes
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.receivers.*
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.sure
import java.lang.IllegalStateException
import kotlin.collections.HashMap

class NewResolutionOldInference(
        private val candidateResolver: CandidateResolver,
        private val towerResolver: TowerResolver,
        private val resolutionResultsHandler: ResolutionResultsHandler,
        private val dynamicCallableDescriptors: DynamicCallableDescriptors,
        private val syntheticScopes: SyntheticScopes,
        private val languageVersionSettings: LanguageVersionSettings,
        private val coroutineInferenceSupport: CoroutineInferenceSupport,
        private val deprecationResolver: DeprecationResolver
) {
    sealed class ResolutionKind<D : CallableDescriptor>(val kotlinCallKind: KotlinCallKind = KotlinCallKind.UNSUPPORTED) {
        abstract internal fun createTowerProcessor(
                outer: NewResolutionOldInference,
                name: Name,
                tracing: TracingStrategy,
                scopeTower: ImplicitScopeTower,
                explicitReceiver: DetailedReceiver?,
                context: BasicCallResolutionContext
        ): ScopeTowerProcessor<MyCandidate>

        object Function : ResolutionKind<FunctionDescriptor>(KotlinCallKind.FUNCTION) {
            override fun createTowerProcessor(
                    outer: NewResolutionOldInference, name: Name, tracing: TracingStrategy,
                    scopeTower: ImplicitScopeTower, explicitReceiver: DetailedReceiver?, context: BasicCallResolutionContext
            ): ScopeTowerProcessor<MyCandidate> {
                val functionFactory = outer.CandidateFactoryImpl(name, context, tracing)
                return createFunctionProcessor(scopeTower, name, functionFactory, outer.CandidateFactoryProviderForInvokeImpl(functionFactory), explicitReceiver)
            }
        }

        object Variable : ResolutionKind<VariableDescriptor>(KotlinCallKind.VARIABLE) {
            override fun createTowerProcessor(
                    outer: NewResolutionOldInference, name: Name, tracing: TracingStrategy,
                    scopeTower: ImplicitScopeTower, explicitReceiver: DetailedReceiver?, context: BasicCallResolutionContext
            ): ScopeTowerProcessor<MyCandidate> {
                val variableFactory = outer.CandidateFactoryImpl(name, context, tracing)
                return createVariableAndObjectProcessor(scopeTower, name, variableFactory, explicitReceiver)
            }
        }

        object CallableReference : ResolutionKind<CallableDescriptor>() {
            override fun createTowerProcessor(
                    outer: NewResolutionOldInference, name: Name, tracing: TracingStrategy,
                    scopeTower: ImplicitScopeTower, explicitReceiver: DetailedReceiver?, context: BasicCallResolutionContext
            ): ScopeTowerProcessor<MyCandidate> {
                val functionFactory = outer.CandidateFactoryImpl(name, context, tracing)
                val variableFactory = outer.CandidateFactoryImpl(name, context, tracing)
                return PrioritizedCompositeScopeTowerProcessor(
                        createSimpleFunctionProcessor(scopeTower, name, functionFactory, explicitReceiver, classValueReceiver = false),
                        createVariableProcessor(scopeTower, name, variableFactory, explicitReceiver, classValueReceiver = false)
                )
            }
        }

        object Invoke : ResolutionKind<FunctionDescriptor>() {
            override fun createTowerProcessor(
                    outer: NewResolutionOldInference, name: Name, tracing: TracingStrategy,
                    scopeTower: ImplicitScopeTower, explicitReceiver: DetailedReceiver?, context: BasicCallResolutionContext
            ): ScopeTowerProcessor<MyCandidate> {
                val functionFactory = outer.CandidateFactoryImpl(name, context, tracing)
                // todo
                val call = (context.call as? CallTransformer.CallForImplicitInvoke).sure {
                    "Call should be CallForImplicitInvoke, but it is: ${context.call}"
                }
                return createProcessorWithReceiverValueOrEmpty(explicitReceiver) {
                    createCallTowerProcessorForExplicitInvoke(scopeTower, functionFactory, context.transformToReceiverWithSmartCastInfo(call.dispatchReceiver), it)
                }
            }

        }

        class GivenCandidates<D : CallableDescriptor> : ResolutionKind<D>() {
            override fun createTowerProcessor(
                    outer: NewResolutionOldInference, name: Name, tracing: TracingStrategy,
                    scopeTower: ImplicitScopeTower, explicitReceiver: DetailedReceiver?, context: BasicCallResolutionContext
            ): ScopeTowerProcessor<MyCandidate> {
                throw IllegalStateException("Should be not called")
            }
        }
    }

    private class CompatCandidate(
            // Call candidate to check
            val candidate: MyCandidate,
            // All classes and interfaces, annotated with @Compat mapped to their compat companions
            val compatClasses: Map<KotlinType, SimpleType>
    )

    private fun findCompatAnnotationOnType(t: KotlinType) =
            t.constructor.declarationDescriptor?.annotations?.firstOrNull { it.fqName == FqName("Compat") }

    // Find all compat annotations on the type and its supertypes
    private fun findCompatAnnotations(type: KotlinType): Map<KotlinType, AnnotationDescriptor> {
        val allTypes = type.supertypes() + type
        val res = hashMapOf<KotlinType, AnnotationDescriptor>()
        for (t in allTypes) {
            val annotation = findCompatAnnotationOnType(t)
            if (annotation != null) res[t] = annotation
        }
        return res
    }

    // Find all compat classes of the type and its supertypes including interfaces
    private fun findCompatClasses(type: KotlinType): Map<KotlinType, SimpleType> {
        val res = hashMapOf<KotlinType, SimpleType>()
        for ((t, annotation) in findCompatAnnotations(type)) {
            res[t] = annotation.argumentValue("value") as? SimpleType ?: continue
        }
        return res
    }

    private fun functionSignaturesEqual(
            call: CallableDescriptor,
            candidate: CallableDescriptor,
            scope: MemberScope,
            originType: KotlinType? = null,
            receiverType: KotlinType? = null
    ): Boolean {
        if (call.name != candidate.name) return false
        if (!KotlinTypeChecker.DEFAULT.equalTypes(call.returnTypeOrNothing, candidate.returnTypeOrNothing)) return false
        if (call.typeParameters.size != candidate.typeParameters.size) return false
        if (call.typeParameters.indices.any { call.typeParameters[it] != candidate.typeParameters[it] }) return false
        if (call.valueParameters.size + (if (originType != null) 1 else 0) != candidate.valueParameters.size) return false
        if (originType != null && !KotlinTypeChecker.DEFAULT.equalTypes(originType, candidate.valueParameters[0].type)) return false
        for (i in call.valueParameters.indices) {
            val j = if (originType != null) i + 1 else i
            if (KotlinTypeChecker.DEFAULT.equalTypes(candidate.valueParameters[j].type, call.valueParameters[i].type)) continue
            // Check for sam adapters
            if (call.valueParameters[i].type.isFunctionType) {
                val synthetics = syntheticScopes.scopes.flatMap {
                    if (receiverType == null) it.getSyntheticStaticFunctions(scope)
                    else it.getSyntheticMemberFunctions(listOf(receiverType))
                }
                val originFun = synthetics.firstOrNull {
                    functionSignaturesEqual(call, it, scope)
                } as? SyntheticMemberDescriptor<*> ?: return false
                val realParam = (originFun.baseDescriptorForSynthetic as? JavaMethodDescriptor)?.valueParameters?.get(i) ?: return false
                if (!KotlinTypeChecker.DEFAULT.equalTypes(candidate.valueParameters[j].type, realParam.type)) return false
            }
            else return false
        }
        return true
    }

    private fun KtPsiFactory.createImplicitThisExpression(descriptor: CallableDescriptor) = KtImplicitThisExpression(createThisExpression().node, descriptor)

    private fun replaceWithCompatCallIfNeeded(candidates: Collection<MyCandidate>): Collection<MyCandidate> {
        val compatCandidates = arrayListOf<CompatCandidate>()
        for (candidate in candidates) {
            val call = candidate.resolvedCall
            val receiver = call.dispatchReceiver ?: continue
            val compatClasses = findCompatClasses(receiver.type)
            if (compatClasses.isNotEmpty()) compatCandidates += CompatCandidate(candidate, compatClasses)
        }
        if (compatCandidates.isEmpty()) return candidates

        // Calls with appropriate compat
        val callsToReplace = hashMapOf<MyCandidate, MyCandidate>()
        // Most of these loops iterate only once. It's OK to let them nest
        for (compatCandidate in compatCandidates) {
            val resolvedCall = compatCandidate.candidate.resolvedCall
            val callDescriptor = resolvedCall.candidateDescriptor ?: continue
            val receiver = resolvedCall.dispatchReceiver ?: continue

            // Find appropriate compat class/method and replace the call
            for ((origin, compat) in compatCandidate.compatClasses) {
                val scope = (compat.constructor.declarationDescriptor as? JavaClassDescriptor)?.staticScope ?: continue
                var compatMethod: CallableDescriptor? = null
                // Find method in compat class
                for (compatMethodDescriptor in scope.getDescriptorsFiltered { it == callDescriptor.name }) {
                    if (compatMethodDescriptor !is JavaMethodDescriptor) continue
                    if (!functionSignaturesEqual(callDescriptor, compatMethodDescriptor, scope, origin, receiver.type)) continue
                    compatMethod = syntheticScopes.scopes.flatMap { it.getSyntheticStaticFunctions(scope) }.firstOrNull {
                        functionSignaturesEqual(callDescriptor, it, scope, origin)
                    } ?: compatMethodDescriptor
                }
                if (compatMethod == null) continue

                // Replace the call
                val psiFactory = KtPsiFactory(resolvedCall.call.callElement, markGenerated = false)
                val calleeExpression = psiFactory.createSimpleName(callDescriptor.name.asString())

                val receiverExpr: KtExpression
                if (receiver is ExpressionReceiver) {
                    receiverExpr = receiver.expression
                } else {
                    val desc = (receiver as? ImplicitReceiver)?.declarationDescriptor as? CallableDescriptor ?: continue
                    receiverExpr = psiFactory.createImplicitThisExpression(desc)
                }
                val originValue = CallMaker.makeValueArgument(receiverExpr)
                val compatCall = CallMaker.makeCall(
                        resolvedCall.call.callElement,
                        null,
                        resolvedCall.call.callOperationNode,
                        calleeExpression,
                        listOf(originValue) + resolvedCall.call.valueArguments
                )
                val compatResolverCall = ResolvedCallImpl(
                        compatCall,
                        compatMethod,
                        null,
                        null,
                        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
                        resolvedCall.knownTypeParametersSubstitutor,
                        resolvedCall.trace,
                        resolvedCall.tracingStrategy,
                        resolvedCall.dataFlowInfoForArguments
                )
                compatResolverCall.recordValueArgument(compatMethod.valueParameters.first(), ExpressionValueArgument(originValue))
                resolvedCall.valueArguments.forEach {
                    compatResolverCall.recordValueArgument(compatMethod!!.valueParameters[it.key.index + 1], it.value)
                }
                // TODO: Hack
                compatResolverCall.setStatusToSuccess()
                callsToReplace[compatCandidate.candidate] = MyCandidate(compatCandidate.candidate.diagnostics, compatResolverCall)
            }
        }

        if (callsToReplace.isEmpty()) return candidates

        val res = arrayListOf<MyCandidate>()
        for (candidate in candidates) res += callsToReplace[candidate] ?: candidate
        return res
    }

    fun <D : CallableDescriptor> runResolution(
            context: BasicCallResolutionContext,
            name: Name,
            kind: ResolutionKind<D>,
            tracing: TracingStrategy
    ): OverloadResolutionResultsImpl<D> {
        val explicitReceiver = context.call.explicitReceiver
        val detailedReceiver = if (explicitReceiver is QualifierReceiver?) {
            explicitReceiver
        }
        else {
            context.transformToReceiverWithSmartCastInfo(explicitReceiver as ReceiverValue)
        }

        val dynamicScope = dynamicCallableDescriptors.createDynamicDescriptorScope(context.call, context.scope.ownerDescriptor)
        val scopeTower = ImplicitScopeTowerImpl(context, dynamicScope, syntheticScopes, context.call.createLookupLocation())

        val shouldUseOperatorRem = languageVersionSettings.supportsFeature(LanguageFeature.OperatorRem)
        val isBinaryRemOperator = isBinaryRemOperator(context.call)
        val nameToResolve = if (isBinaryRemOperator && !shouldUseOperatorRem)
            OperatorConventions.REM_TO_MOD_OPERATION_NAMES[name]!!
        else
            name

        val processor = kind.createTowerProcessor(this, nameToResolve, tracing, scopeTower, detailedReceiver, context)

        if (context.collectAllCandidates) {
            return allCandidatesResult(towerResolver.collectAllCandidates(scopeTower, processor, nameToResolve))
        }

        var candidates = towerResolver.runResolve(scopeTower, processor, useOrder = kind != ResolutionKind.CallableReference, name = nameToResolve)

        // Temporary hack to resolve 'rem' as 'mod' if the first is do not present
        val emptyOrInapplicableCandidates = candidates.isEmpty() ||
                                            candidates.all { it.resultingApplicability.isInapplicable }
        if (isBinaryRemOperator && shouldUseOperatorRem && emptyOrInapplicableCandidates) {
            val deprecatedName = OperatorConventions.REM_TO_MOD_OPERATION_NAMES[name]
            val processorForDeprecatedName = kind.createTowerProcessor(this, deprecatedName!!, tracing, scopeTower, detailedReceiver, context)
            candidates = towerResolver.runResolve(scopeTower, processorForDeprecatedName, useOrder = kind != ResolutionKind.CallableReference, name = deprecatedName)
        }

        candidates = replaceWithCompatCallIfNeeded(candidates)

        if (candidates.isEmpty()) {
            if (reportAdditionalDiagnosticIfNoCandidates(context, nameToResolve, kind, scopeTower, detailedReceiver)) {
                return OverloadResolutionResultsImpl.nameNotFound()
            }
        }

        val overloadResults = convertToOverloadResults<D>(candidates, tracing, context, languageVersionSettings)
        coroutineInferenceSupport.checkCoroutineCalls(context, tracing, overloadResults)
        return overloadResults
    }

    fun <D : CallableDescriptor> runResolutionForGivenCandidates(
            basicCallContext: BasicCallResolutionContext,
            tracing: TracingStrategy,
            candidates: Collection<ResolutionCandidate<D>>
    ): OverloadResolutionResultsImpl<D> {
        val resolvedCandidates = candidates.map { candidate ->
            val candidateTrace = TemporaryBindingTrace.create(basicCallContext.trace, "Context for resolve candidate")
            val resolvedCall = ResolvedCallImpl.create(candidate, candidateTrace, tracing, basicCallContext.dataFlowInfoForArguments)

            if (deprecationResolver.isHiddenInResolution(candidate.descriptor, basicCallContext.isSuperCall)) {
                return@map MyCandidate(listOf(HiddenDescriptor), resolvedCall)
            }

            val callCandidateResolutionContext = CallCandidateResolutionContext.create(
                    resolvedCall, basicCallContext, candidateTrace, tracing, basicCallContext.call,
                    CandidateResolveMode.FULLY // todo
            )
            candidateResolver.performResolutionForCandidateCall(callCandidateResolutionContext, basicCallContext.checkArguments) // todo

            val diagnostics = listOfNotNull(createPreviousResolveError(resolvedCall.status))
            MyCandidate(diagnostics, resolvedCall)
        }
        if (basicCallContext.collectAllCandidates) {
            val allCandidates = towerResolver.runWithEmptyTowerData(KnownResultProcessor(resolvedCandidates),
                                                  TowerResolver.AllCandidatesCollector(), useOrder = false)
            return allCandidatesResult(allCandidates)
        }

        val processedCandidates = towerResolver.runWithEmptyTowerData(KnownResultProcessor(resolvedCandidates),
                                                    TowerResolver.SuccessfulResultCollector(), useOrder = true)

        return convertToOverloadResults(processedCandidates, tracing, basicCallContext, languageVersionSettings)
    }

    private fun <D: CallableDescriptor> allCandidatesResult(allCandidates: Collection<MyCandidate>)
            = OverloadResolutionResultsImpl.nameNotFound<D>().apply {
        this.allCandidates = allCandidates.map { it.resolvedCall as MutableResolvedCall<D> }
    }

    private fun <D : CallableDescriptor> convertToOverloadResults(
            candidates: Collection<MyCandidate>,
            tracing: TracingStrategy,
            basicCallContext: BasicCallResolutionContext,
            languageVersionSettings: LanguageVersionSettings
    ): OverloadResolutionResultsImpl<D> {
        val resolvedCalls = candidates.map {
            val (diagnostics, resolvedCall) = it
            if (resolvedCall is VariableAsFunctionResolvedCallImpl) {
                // todo hacks
                tracing.bindReference(resolvedCall.variableCall.trace, resolvedCall.variableCall)
                tracing.bindResolvedCall(resolvedCall.variableCall.trace, resolvedCall)

                resolvedCall.variableCall.trace.addOwnDataTo(resolvedCall.functionCall.trace)

                resolvedCall.functionCall.tracingStrategy.bindReference(resolvedCall.functionCall.trace, resolvedCall.functionCall)
                //                resolvedCall.hackInvokeTracing.bindResolvedCall(resolvedCall.functionCall.trace, resolvedCall)
            } else {
                tracing.bindReference(resolvedCall.trace, resolvedCall)
                tracing.bindResolvedCall(resolvedCall.trace, resolvedCall)
            }

            if (resolvedCall.status.possibleTransformToSuccess()) {
                for (error in diagnostics) {
                    when (error) {
                        is UnsupportedInnerClassCall -> resolvedCall.trace.report(Errors.UNSUPPORTED.on(resolvedCall.call.callElement, error.message))
                        is NestedClassViaInstanceReference -> tracing.nestedClassAccessViaInstanceReference(resolvedCall.trace, error.classDescriptor, resolvedCall.explicitReceiverKind)
                        is ErrorDescriptorDiagnostic -> {
                            // todo
                            //  return@map null
                        }
                    }
                }
            }

            resolvedCall as MutableResolvedCall<D>
        }

        return resolutionResultsHandler.computeResultAndReportErrors(basicCallContext, tracing, resolvedCalls, languageVersionSettings)
    }

    // true if we found something
    private fun reportAdditionalDiagnosticIfNoCandidates(
            context: BasicCallResolutionContext,
            name: Name,
            kind: ResolutionKind<*>,
            scopeTower: ImplicitScopeTower,
            detailedReceiver: DetailedReceiver?
    ): Boolean {
        val reference = context.call.calleeExpression as? KtReferenceExpression ?: return false

        val errorCandidates = when (kind) {
            ResolutionKind.Function -> collectErrorCandidatesForFunction(scopeTower, name, detailedReceiver)
            ResolutionKind.Variable -> collectErrorCandidatesForVariable(scopeTower, name, detailedReceiver)
            else -> emptyList()
        }

        val candidate = errorCandidates.firstOrNull() as? ErrorCandidate.Classifier ?: return false

        context.trace.record(BindingContext.REFERENCE_TARGET, reference, candidate.descriptor)
        context.trace.report(Errors.RESOLUTION_TO_CLASSIFIER.on(reference, candidate.descriptor, candidate.kind, candidate.errorMessage))

        return true
    }

    private class ImplicitScopeTowerImpl(
            val resolutionContext: ResolutionContext<*>,
            override val dynamicScope: MemberScope,
            override val syntheticScopes: SyntheticScopes,
            override val location: LookupLocation
    ): ImplicitScopeTower {
        private val cache = HashMap<ReceiverValue, ReceiverValueWithSmartCastInfo>()

        override fun getImplicitReceiver(scope: LexicalScope): ReceiverValueWithSmartCastInfo? =
                scope.implicitReceiver?.value?.let {
                    cache.getOrPut(it) { resolutionContext.transformToReceiverWithSmartCastInfo(it) }
                }

        override val lexicalScope: LexicalScope get() = resolutionContext.scope

        override val isDebuggerContext: Boolean get() = resolutionContext.isDebuggerContext
    }

    internal data class MyCandidate(
            val diagnostics: List<KotlinCallDiagnostic>,
            val resolvedCall: MutableResolvedCall<*>
    ) : Candidate {
        override val resultingApplicability: ResolutionCandidateApplicability = getResultApplicability(diagnostics)
        override val isSuccessful get() = resultingApplicability.isSuccess
    }

    private inner class CandidateFactoryImpl(
            val name: Name,
            val basicCallContext: BasicCallResolutionContext,
            val tracing: TracingStrategy
    ) : CandidateFactory<MyCandidate> {
        override fun createCandidate(
                towerCandidate: CandidateWithBoundDispatchReceiver,
                explicitReceiverKind: ExplicitReceiverKind,
                extensionReceiver: ReceiverValueWithSmartCastInfo?
        ): MyCandidate {

            val candidateTrace = TemporaryBindingTrace.create(basicCallContext.trace, "Context for resolve candidate")
            val candidateCall = ResolvedCallImpl(
                    basicCallContext.call, towerCandidate.descriptor,
                    towerCandidate.dispatchReceiver?.receiverValue, extensionReceiver?.receiverValue,
                    explicitReceiverKind, null, candidateTrace, tracing,
                    basicCallContext.dataFlowInfoForArguments // todo may be we should create new mutable info for arguments
            )

            /**
             * See https://jetbrains.quip.com/qcTDAFcgFLEM
             *
             * For now we have only 2 functions with dynamic receivers: iterator() and unsafeCast()
             * Both this function are marked via @kotlin.internal.DynamicExtension.
             */
            if (extensionReceiver != null) {
                val parameterIsDynamic = towerCandidate.descriptor.extensionReceiverParameter!!.value.type.isDynamic()
                val argumentIsDynamic = extensionReceiver.receiverValue.type.isDynamic()

                if (parameterIsDynamic != argumentIsDynamic ||
                    (parameterIsDynamic && !towerCandidate.descriptor.hasDynamicExtensionAnnotation())
                ) {
                    return MyCandidate(listOf(HiddenExtensionRelatedToDynamicTypes), candidateCall)
                }
            }


            if (deprecationResolver.isHiddenInResolution(towerCandidate.descriptor, basicCallContext.isSuperCall)) {
                return MyCandidate(listOf(HiddenDescriptor), candidateCall)
            }

            val callCandidateResolutionContext = CallCandidateResolutionContext.create(
                    candidateCall, basicCallContext, candidateTrace, tracing, basicCallContext.call,
                    CandidateResolveMode.FULLY // todo
            )
            candidateResolver.performResolutionForCandidateCall(callCandidateResolutionContext, basicCallContext.checkArguments) // todo

            val diagnostics = (towerCandidate.diagnostics +
                               checkInfixAndOperator(basicCallContext.call, towerCandidate.descriptor) +
                               createPreviousResolveError(candidateCall.status)).filterNotNull() // todo
            return MyCandidate(diagnostics, candidateCall)
        }

        private fun checkInfixAndOperator(call: Call, descriptor: CallableDescriptor): List<ResolutionDiagnostic> {
            if (descriptor !is FunctionDescriptor || ErrorUtils.isError(descriptor)) return emptyList()
            if (descriptor.name != name && (name == OperatorNameConventions.UNARY_PLUS || name == OperatorNameConventions.UNARY_MINUS)) {
                return listOf(DeprecatedUnaryPlusAsPlus)
            }

            val conventionError = if (isConventionCall(call) && !descriptor.isOperator) InvokeConventionCallNoOperatorModifier else null
            val infixError = if (isInfixCall(call) && !descriptor.isInfix) InfixCallNoInfixModifier else null
            return listOfNotNull(conventionError, infixError)
        }

    }

    private inner class CandidateFactoryProviderForInvokeImpl(
            val functionContext: CandidateFactoryImpl
    ) : CandidateFactoryProviderForInvoke<MyCandidate> {

        override fun transformCandidate(
                variable: MyCandidate,
                invoke: MyCandidate
        ): MyCandidate {
            val resolvedCallImpl = VariableAsFunctionResolvedCallImpl(
                    invoke.resolvedCall as MutableResolvedCall<FunctionDescriptor>,
                    variable.resolvedCall as MutableResolvedCall<VariableDescriptor>
            )
            assert(variable.resultingApplicability.isSuccess) {
                "Variable call must be success: $variable"
            }

            return MyCandidate(variable.diagnostics + invoke.diagnostics, resolvedCallImpl)
        }

        override fun factoryForVariable(stripExplicitReceiver: Boolean): CandidateFactory<MyCandidate> {
            val newCall = CallTransformer.stripCallArguments(functionContext.basicCallContext.call).let {
                if (stripExplicitReceiver) CallTransformer.stripReceiver(it) else it
            }
            return CandidateFactoryImpl(functionContext.name, functionContext.basicCallContext.replaceCall(newCall), functionContext.tracing)
        }

        override fun factoryForInvoke(
                variable: MyCandidate,
                useExplicitReceiver: Boolean
        ): Pair<ReceiverValueWithSmartCastInfo, CandidateFactory<MyCandidate>>? {
            assert(variable.resolvedCall.status.possibleTransformToSuccess()) {
                "Incorrect status: ${variable.resolvedCall.status} for variable call: ${variable.resolvedCall} " +
                "and descriptor: ${variable.resolvedCall.candidateDescriptor}"
            }
            val calleeExpression = variable.resolvedCall.call.calleeExpression
            val variableDescriptor = variable.resolvedCall.resultingDescriptor as VariableDescriptor
            assert(variable.resolvedCall.status.possibleTransformToSuccess() && calleeExpression != null) {
                "Unexpected variable candidate: $variable"
            }
            val variableType = variableDescriptor.type

            if (variableType is DeferredType && variableType.isComputing) {
                return null // todo: create special check that there is no invoke on variable
            }
            val basicCallContext = functionContext.basicCallContext
            val variableReceiver = ExpressionReceiver.create(calleeExpression!!,
                                                             variableType,
                                                             basicCallContext.trace.bindingContext)
            // used for smartCasts, see: DataFlowValueFactory.getIdForSimpleNameExpression
            functionContext.tracing.bindReference(variable.resolvedCall.trace, variable.resolvedCall)
            // todo hacks
            val functionCall = CallTransformer.CallForImplicitInvoke(
                    basicCallContext.call.explicitReceiver?.takeIf { useExplicitReceiver },
                    variableReceiver, basicCallContext.call, true)
            val tracingForInvoke = TracingStrategyForInvoke(calleeExpression, functionCall, variableReceiver.type)
            val basicCallResolutionContext = basicCallContext.replaceBindingTrace(variable.resolvedCall.trace)
                    .replaceCall(functionCall)
                    .replaceContextDependency(ContextDependency.DEPENDENT) // todo

            val newContext = CandidateFactoryImpl(OperatorNameConventions.INVOKE, basicCallResolutionContext, tracingForInvoke)

            return basicCallResolutionContext.transformToReceiverWithSmartCastInfo(variableReceiver) to newContext
        }

    }

}

fun ResolutionContext<*>.transformToReceiverWithSmartCastInfo(receiver: ReceiverValue) =
        transformToReceiverWithSmartCastInfo(scope.ownerDescriptor, trace.bindingContext, dataFlowInfo, receiver)

fun transformToReceiverWithSmartCastInfo(
        containingDescriptor: DeclarationDescriptor,
        bindingContext: BindingContext,
        dataFlowInfo: DataFlowInfo,
        receiver: ReceiverValue
): ReceiverValueWithSmartCastInfo {
    val dataFlowValue = DataFlowValueFactory.createDataFlowValue(receiver, bindingContext, containingDescriptor)
    return ReceiverValueWithSmartCastInfo(receiver, dataFlowInfo.getCollectedTypes(dataFlowValue), dataFlowValue.isStable)
}

@Deprecated("Temporary error")
internal class PreviousResolutionError(candidateLevel: ResolutionCandidateApplicability): ResolutionDiagnostic(candidateLevel)

@Deprecated("Temporary error")
internal fun createPreviousResolveError(status: ResolutionStatus): PreviousResolutionError? {
    val level = when (status) {
        ResolutionStatus.SUCCESS, ResolutionStatus.INCOMPLETE_TYPE_INFERENCE -> return null
        ResolutionStatus.UNSAFE_CALL_ERROR -> ResolutionCandidateApplicability.MAY_THROW_RUNTIME_ERROR
        ResolutionStatus.ARGUMENTS_MAPPING_ERROR -> ResolutionCandidateApplicability.INAPPLICABLE_ARGUMENTS_MAPPING_ERROR
        ResolutionStatus.RECEIVER_TYPE_ERROR -> ResolutionCandidateApplicability.INAPPLICABLE_WRONG_RECEIVER
        else -> ResolutionCandidateApplicability.INAPPLICABLE
    }
    return PreviousResolutionError(level)
}

private val BasicCallResolutionContext.isSuperCall: Boolean get() = call.explicitReceiver is SuperCallReceiverValue
