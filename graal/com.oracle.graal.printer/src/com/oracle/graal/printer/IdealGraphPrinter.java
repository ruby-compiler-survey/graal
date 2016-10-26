/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.printer;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.oracle.graal.bytecode.BytecodeDisassembler;
import com.oracle.graal.debug.DebugDumpHandler.TrustedObjectConstantFormatter;
import com.oracle.graal.debug.GraalDebugConfig.Options;
import com.oracle.graal.graph.Graph;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeMap;
import com.oracle.graal.graph.Position;
import com.oracle.graal.nodeinfo.Verbosity;
import com.oracle.graal.nodes.AbstractMergeNode;
import com.oracle.graal.nodes.BeginNode;
import com.oracle.graal.nodes.EndNode;
import com.oracle.graal.nodes.ParameterNode;
import com.oracle.graal.nodes.PhiNode;
import com.oracle.graal.nodes.StateSplit;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.ScheduleResult;
import com.oracle.graal.nodes.cfg.Block;
import com.oracle.graal.nodes.cfg.ControlFlowGraph;
import com.oracle.graal.phases.schedule.SchedulePhase;

import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Generates a representation of {@link Graph Graphs} that can be visualized and inspected with the
 * <a href="http://kenai.com/projects/igv">Ideal Graph Visualizer</a>.
 */
public class IdealGraphPrinter extends BasicIdealGraphPrinter implements GraphPrinter {

    private final boolean tryToSchedule;

    /**
     * Creates a new {@link IdealGraphPrinter} that writes to the specified output stream.
     *
     * @param tryToSchedule If false, no scheduling is done, which avoids exceptions for
     *            non-schedulable graphs.
     */
    public IdealGraphPrinter(OutputStream stream, boolean tryToSchedule) {
        super(stream);
        this.begin();
        this.tryToSchedule = tryToSchedule;
    }

    /**
     * Starts a new group of graphs with the given name, short name and method byte code index (BCI)
     * as properties.
     */
    @Override
    public void beginGroup(String name, String shortName, ResolvedJavaMethod method, int bci, Map<Object, Object> properties, TrustedObjectConstantFormatter formatter) {
        beginGroup();
        beginProperties();
        printProperty("name", name);
        if (properties != null) {
            for (Entry<Object, Object> entry : properties.entrySet()) {
                printProperty(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        endProperties();
        beginMethod(name, shortName, bci);
        if (method != null && method.getCode() != null) {
            printBytecodes(new BytecodeDisassembler(false).disassemble(method));
        }
        endMethod();
    }

    /**
     * Prints an entire {@link Graph} with the specified title, optionally using short names for
     * nodes.
     */
    @Override
    public void print(Graph graph, String title, Map<Object, Object> properties, TrustedObjectConstantFormatter formatter) {
        beginGraph(title);
        Set<Node> noBlockNodes = Node.newSet();
        ScheduleResult schedule = null;
        if (graph instanceof StructuredGraph) {
            StructuredGraph structuredGraph = (StructuredGraph) graph;
            schedule = structuredGraph.getLastSchedule();
            if (schedule == null && tryToSchedule) {
                if (Options.PrintIdealGraphSchedule.getValue()) {
                    try {
                        SchedulePhase schedulePhase = new SchedulePhase();
                        schedulePhase.apply(structuredGraph);
                        schedule = structuredGraph.getLastSchedule();
                    } catch (Throwable t) {
                    }
                }
            }
        }
        ControlFlowGraph cfg = schedule == null ? null : schedule.getCFG();

        if (properties != null) {
            beginProperties();
            for (Entry<Object, Object> entry : properties.entrySet()) {
                printProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            endProperties();
        }

        beginNodes();
        List<Edge> edges = printNodes(graph, cfg == null ? null : cfg.getNodeToBlock(), noBlockNodes);
        endNodes();

        beginEdges();
        for (Edge edge : edges) {
            printEdge(edge);
        }
        endEdges();

        if (cfg != null && cfg.getBlocks() != null) {
            beginControlFlow();
            for (Block block : cfg.getBlocks()) {
                printBlock(graph, block, cfg.getNodeToBlock());
            }
            printNoBlock(noBlockNodes);
            endControlFlow();
        }

        endGraph();
        flush();
    }

    private List<Edge> printNodes(Graph graph, NodeMap<Block> nodeToBlock, Set<Node> noBlockNodes) {
        ArrayList<Edge> edges = new ArrayList<>();

        NodeMap<Set<Entry<String, Integer>>> colors = graph.createNodeMap();
        NodeMap<Set<Entry<String, String>>> colorsToString = graph.createNodeMap();
        NodeMap<Set<String>> bits = graph.createNodeMap();

        for (Node node : graph.getNodes()) {

            beginNode(node.toString(Verbosity.Id));
            beginProperties();
            printProperty("idx", node.toString(Verbosity.Id));

            Map<Object, Object> props = node.getDebugProperties();
            if (!props.containsKey("name") || props.get("name").toString().trim().length() == 0) {
                String name = node.toString(Verbosity.Name);
                printProperty("name", name);
            }
            printProperty("class", node.getClass().getSimpleName());

            Block block = nodeToBlock == null || nodeToBlock.isNew(node) ? null : nodeToBlock.get(node);
            if (block != null) {
                printProperty("block", Integer.toString(block.getId()));
                // if (!(node instanceof PhiNode || node instanceof FrameState || node instanceof
                // ParameterNode) && !block.nodes().contains(node)) {
                // printProperty("notInOwnBlock", "true");
                // }
            } else {
                printProperty("block", "noBlock");
                noBlockNodes.add(node);
            }

            Set<Entry<String, Integer>> nodeColors = colors.get(node);
            if (nodeColors != null) {
                for (Entry<String, Integer> color : nodeColors) {
                    String name = color.getKey();
                    Integer value = color.getValue();
                    printProperty(name, Integer.toString(value));
                }
            }
            Set<Entry<String, String>> nodeColorStrings = colorsToString.get(node);
            if (nodeColorStrings != null) {
                for (Entry<String, String> color : nodeColorStrings) {
                    String name = color.getKey();
                    String value = color.getValue();
                    printProperty(name, value);
                }
            }
            Set<String> nodeBits = bits.get(node);
            if (nodeBits != null) {
                for (String bit : nodeBits) {
                    printProperty(bit, "true");
                }
            }
            if (node instanceof BeginNode) {
                printProperty("shortName", "B");
            } else if (node.getClass() == EndNode.class) {
                printProperty("shortName", "E");
            }
            if (node.predecessor() != null) {
                printProperty("hasPredecessor", "true");
            }

            for (Entry<Object, Object> entry : props.entrySet()) {
                String key = entry.getKey().toString();
                Object value = entry.getValue();
                String valueString;
                if (value == null) {
                    valueString = "null";
                } else {
                    Class<?> type = value.getClass();
                    if (type.isArray()) {
                        if (!type.getComponentType().isPrimitive()) {
                            valueString = Arrays.toString((Object[]) value);
                        } else if (type.getComponentType() == Integer.TYPE) {
                            valueString = Arrays.toString((int[]) value);
                        } else if (type.getComponentType() == Double.TYPE) {
                            valueString = Arrays.toString((double[]) value);
                        } else {
                            valueString = toString();
                        }
                    } else {
                        valueString = value.toString();
                    }
                }
                printProperty(key, valueString);
            }

            endProperties();
            endNode();

            // successors
            int fromIndex = 0;
            for (Position position : node.successorPositions()) {
                Node successor = position.get(node);
                if (successor != null) {
                    edges.add(new Edge(node.toString(Verbosity.Id), fromIndex, successor.toString(Verbosity.Id), 0, position.getName()));
                }
                fromIndex++;
            }

            // inputs
            int toIndex = 1;
            for (Position position : node.inputPositions()) {
                Node input = position.get(node);
                if (input != null) {
                    edges.add(new Edge(input.toString(Verbosity.Id), input.successors().count(), node.toString(Verbosity.Id), toIndex, position.getName()));
                }
                toIndex++;
            }
        }

        return edges;
    }

    private void printBlock(Graph graph, Block block, NodeMap<Block> nodeToBlock) {
        beginBlock(Integer.toString(block.getId()));
        beginSuccessors();
        for (Block sux : block.getSuccessors()) {
            if (sux != null) {
                printSuccessor(Integer.toString(sux.getId()));
            }
        }
        endSuccessors();
        beginBlockNodes();

        Set<Node> nodes = Node.newSet();

        if (nodeToBlock != null) {
            for (Node n : graph.getNodes()) {
                Block blk = nodeToBlock.isNew(n) ? null : nodeToBlock.get(n);
                if (blk == block) {
                    nodes.add(n);
                }
            }
        }

        if (nodes.size() > 0) {
            // if this is the first block: add all locals to this block
            if (block.getBeginNode() == ((StructuredGraph) graph).start()) {
                for (Node node : graph.getNodes()) {
                    if (node instanceof ParameterNode) {
                        nodes.add(node);
                    }
                }
            }

            Set<Node> snapshot = Node.newSet(nodes);
            // add all framestates and phis to their blocks
            for (Node node : snapshot) {
                if (node instanceof StateSplit && ((StateSplit) node).stateAfter() != null) {
                    nodes.add(((StateSplit) node).stateAfter());
                }
                if (node instanceof AbstractMergeNode) {
                    for (PhiNode phi : ((AbstractMergeNode) node).phis()) {
                        nodes.add(phi);
                    }
                }
            }

            for (Node node : nodes) {
                printBlockNode(node.toString(Verbosity.Id));
            }
        }
        endBlockNodes();
        endBlock();
    }

    private void printNoBlock(Set<Node> noBlockNodes) {
        if (!noBlockNodes.isEmpty()) {
            beginBlock("noBlock");
            beginBlockNodes();
            for (Node node : noBlockNodes) {
                printBlockNode(node.toString(Verbosity.Id));
            }
            endBlockNodes();
            endBlock();
        }
    }
}
