/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.nodes.dfa;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.regex.RegexRootNode;
import com.oracle.truffle.regex.tregex.nodes.TRegexExecutorLocals;
import com.oracle.truffle.regex.tregex.nodes.TRegexExecutorNode;

public final class TRegexDFAExecutorNode extends TRegexExecutorNode {

    public static final int NO_MATCH = -2;
    private final TRegexDFAExecutorProperties props;
    private final int maxNumberOfNFAStates;
    @Children private final DFAAbstractStateNode[] states;
    @CompilationFinal(dimensions = 1) private final DFACaptureGroupLazyTransition[] cgTransitions;
    private final TRegexDFAExecutorDebugRecorder debugRecorder;

    public TRegexDFAExecutorNode(
                    TRegexDFAExecutorProperties props,
                    int maxNumberOfNFAStates,
                    DFAAbstractStateNode[] states,
                    DFACaptureGroupLazyTransition[] cgTransitions,
                    TRegexDFAExecutorDebugRecorder debugRecorder) {
        this.props = props;
        this.maxNumberOfNFAStates = maxNumberOfNFAStates;
        this.states = states;
        this.cgTransitions = cgTransitions;
        this.debugRecorder = debugRecorder;
    }

    public TRegexDFAExecutorNode(
                    TRegexDFAExecutorProperties props,
                    int maxNumberOfNFAStates,
                    DFAAbstractStateNode[] states,
                    DFACaptureGroupLazyTransition[] cgTransitions) {
        this(props, maxNumberOfNFAStates, states, cgTransitions, null);
    }

    private DFAInitialStateNode getInitialState() {
        return (DFAInitialStateNode) states[0];
    }

    public int getPrefixLength() {
        return getInitialState().getPrefixLength();
    }

    public boolean isAnchored() {
        return !getInitialState().hasUnAnchoredEntry();
    }

    public boolean isForward() {
        return props.isForward();
    }

    public boolean isBackward() {
        return !props.isForward();
    }

    public boolean isSearching() {
        return props.isSearching();
    }

    public boolean isSimpleCG() {
        return props.isSimpleCG();
    }

    public boolean isGenericCG() {
        return props.isGenericCG();
    }

    public boolean isRegressionTestMode() {
        return props.isRegressionTestMode();
    }

    public DFACaptureGroupLazyTransition[] getCGTransitions() {
        return cgTransitions;
    }

    public int getNumberOfStates() {
        return states.length;
    }

    public boolean recordExecution() {
        return debugRecorder != null;
    }

    public TRegexDFAExecutorDebugRecorder getDebugRecorder() {
        return debugRecorder;
    }

    @Override
    public TRegexExecutorLocals createLocals(Object input, int fromIndex, int index, int maxIndex) {
        return new TRegexDFAExecutorLocals(input, fromIndex, index, maxIndex, createCGData());
    }

    @Override
    public boolean writesCaptureGroups() {
        return isSimpleCG();
    }

    private DFACaptureGroupTrackingData createCGData() {
        if (isGenericCG() || isSimpleCG()) {
            return new DFACaptureGroupTrackingData(getMaxNumberOfNFAStates(), getNumberOfCaptureGroups(), props);
        } else {
            return null;
        }
    }

    /**
     * records position of the END of the match found, or -1 if no match exists.
     */
    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.MERGE_EXPLODE)
    public Object execute(final TRegexExecutorLocals abstractLocals, final boolean compactString) {
        TRegexDFAExecutorLocals locals = (TRegexDFAExecutorLocals) abstractLocals;
        CompilerDirectives.ensureVirtualized(locals);
        CompilerAsserts.compilationConstant(states);
        CompilerAsserts.compilationConstant(states.length);
        if (!validArgs(locals)) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalArgumentException(String.format("Got illegal args! (fromIndex %d, initialIndex %d, maxIndex %d)",
                            locals.getFromIndex(), locals.getIndex(), locals.getMaxIndex()));
        }
        if (isGenericCG()) {
            initResultOrder(locals);
            locals.setLastTransition((short) -1);
        } else if (isSimpleCG()) {
            CompilerDirectives.ensureVirtualized(locals.getCGData());
            Arrays.fill(locals.getCGData().results, -1);
        }
        // check if input is long enough for a match
        if (props.getMinResultLength() > 0 && (isForward() ? locals.getMaxIndex() - locals.getIndex() : locals.getIndex() - locals.getMaxIndex()) < props.getMinResultLength()) {
            // no match possible, break immediately
            return isGenericCG() || isSimpleCG() ? null : TRegexDFAExecutorNode.NO_MATCH;
        }
        if (recordExecution()) {
            debugRecorder.startRecording(locals);
        }
        if (isBackward() && locals.getFromIndex() - 1 > locals.getMaxIndex()) {
            locals.setCurMaxIndex(locals.getFromIndex() - 1);
        } else {
            locals.setCurMaxIndex(locals.getMaxIndex());
        }
        int ip = 0;
        outer: while (true) {
            if (CompilerDirectives.inInterpreter()) {
                RegexRootNode.checkThreadInterrupted();
            }
            CompilerAsserts.partialEvaluationConstant(ip);
            if (ip == -1) {
                break;
            }
            final DFAAbstractStateNode curState = states[ip];
            CompilerAsserts.partialEvaluationConstant(curState);
            final short[] successors = curState.getSuccessors();
            CompilerAsserts.partialEvaluationConstant(successors);
            CompilerAsserts.partialEvaluationConstant(successors.length);
            int prevIndex = locals.getIndex();
            curState.executeFindSuccessor(locals, this, compactString);
            if (recordExecution() && ip != 0) {
                debugRecordTransition(locals, (DFAStateNode) curState, prevIndex);
            }
            for (int i = 0; i < successors.length; i++) {
                if (i == locals.getSuccessorIndex()) {
                    if (successors[i] != -1 && states[successors[i]] instanceof DFAStateNode) {
                        ((DFAStateNode) states[successors[i]]).getStateReachedProfile().enter();
                    }
                    ip = successors[i];
                    continue outer;
                }
            }
            assert locals.getSuccessorIndex() == -1 : "successorIndex: " + locals.getSuccessorIndex() + ", successors: " + Arrays.toString(successors);
            break;
        }
        if (recordExecution()) {
            debugRecorder.finishRecording();
        }
        if (isSimpleCG()) {
            int[] result = props.isSimpleCGMustCopy() ? locals.getCGData().currentResult : locals.getCGData().results;
            return locals.getResultInt() == 0 ? result : null;
        }
        if (isGenericCG()) {
            return locals.getResultInt() == 0 ? locals.getCGData().currentResult : null;
        }
        return locals.getResultInt();
    }

    private void debugRecordTransition(TRegexDFAExecutorLocals locals, DFAStateNode curState, int prevIndex) {
        short ip = curState.getId();
        boolean hasSuccessor = locals.getSuccessorIndex() != -1;
        if (isForward()) {
            for (int i = prevIndex; i < locals.getIndex() - (hasSuccessor ? 1 : 0); i++) {
                if (curState.hasLoopToSelf()) {
                    debugRecorder.recordTransition(i, ip, curState.getLoopToSelf());
                }
            }
        } else {
            for (int i = prevIndex; i > locals.getIndex() + (hasSuccessor ? 1 : 0); i--) {
                if (curState.hasLoopToSelf()) {
                    debugRecorder.recordTransition(i, ip, curState.getLoopToSelf());
                }
            }
        }
        if (hasSuccessor) {
            debugRecorder.recordTransition(locals.getIndex() + (isForward() ? -1 : 1), ip, locals.getSuccessorIndex());
        }
    }

    public void advance(TRegexDFAExecutorLocals locals) {
        locals.setIndex(props.isForward() ? locals.getIndex() + 1 : locals.getIndex() - 1);
    }

    public boolean hasNext(TRegexDFAExecutorLocals locals) {
        return props.isForward() ? Integer.compareUnsigned(locals.getIndex(), locals.getCurMaxIndex()) < 0 : locals.getIndex() > locals.getCurMaxIndex();
    }

    public boolean atBegin(TRegexDFAExecutorLocals locals) {
        return locals.getIndex() == (props.isForward() ? 0 : getInputLength(locals) - 1);
    }

    public boolean atEnd(TRegexDFAExecutorLocals locals) {
        final int i = locals.getIndex();
        if (props.isForward()) {
            return i == getInputLength(locals);
        } else {
            return i < 0;
        }
    }

    public int rewindUpTo(TRegexDFAExecutorLocals locals, int length) {
        if (props.isForward()) {
            final int offset = Math.min(locals.getIndex(), length);
            locals.setIndex(locals.getIndex() - offset);
            return offset;
        } else {
            assert length == 0;
            return 0;
        }
    }

    private boolean validArgs(TRegexDFAExecutorLocals locals) {
        final int initialIndex = locals.getIndex();
        final int inputLength = getInputLength(locals);
        final int fromIndex = locals.getFromIndex();
        final int maxIndex = locals.getMaxIndex();
        if (props.isForward()) {
            return inputLength >= 0 && inputLength < Integer.MAX_VALUE - 20 &&
                            fromIndex >= 0 && fromIndex <= inputLength &&
                            initialIndex >= 0 && initialIndex <= inputLength &&
                            maxIndex >= 0 && maxIndex <= inputLength &&
                            initialIndex <= maxIndex;
        } else {
            return inputLength >= 0 && inputLength < Integer.MAX_VALUE - 20 &&
                            fromIndex >= 0 && fromIndex <= inputLength &&
                            initialIndex >= -1 && initialIndex < inputLength &&
                            maxIndex >= -1 && maxIndex < inputLength &&
                            initialIndex >= maxIndex;
        }
    }

    @ExplodeLoop
    private void initResultOrder(TRegexDFAExecutorLocals locals) {
        DFACaptureGroupTrackingData cgData = locals.getCGData();
        for (int i = 0; i < maxNumberOfNFAStates; i++) {
            cgData.currentResultOrder[i] = i * getNumberOfCaptureGroups() * 2;
        }
    }

    public TRegexDFAExecutorProperties getProperties() {
        return props;
    }

    public int getMaxNumberOfNFAStates() {
        return maxNumberOfNFAStates;
    }

    public double getCGReorderRatio() {
        if (!isGenericCG()) {
            return 0;
        }
        int nPT = 0;
        int nReorder = 0;
        for (DFACaptureGroupLazyTransition t : cgTransitions) {
            nPT += t.getPartialTransitions().length;
            for (DFACaptureGroupPartialTransition pt : t.getPartialTransitions()) {
                if (pt.doesReorderResults()) {
                    nReorder++;
                }
            }
        }
        if (nPT > 0) {
            return (double) nReorder / nPT;
        }
        return 0;
    }

    public double getCGArrayCopyRatio() {
        if (!isGenericCG()) {
            return 0;
        }
        int nPT = 0;
        int nArrayCopy = 0;
        for (DFACaptureGroupLazyTransition t : cgTransitions) {
            nPT += t.getPartialTransitions().length;
            for (DFACaptureGroupPartialTransition pt : t.getPartialTransitions()) {
                nArrayCopy += pt.getArrayCopies().length / 2;
            }
        }
        if (nPT > 0) {
            return (double) nArrayCopy / nPT;
        }
        return 0;
    }
}
