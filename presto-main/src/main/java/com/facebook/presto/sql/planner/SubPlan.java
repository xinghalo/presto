/*
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
package com.facebook.presto.sql.planner;

import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanFragmentId;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.RemoteSourceNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.graph.Traverser;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMultiset.toImmutableMultiset;
import static com.google.common.collect.Streams.stream;
import static java.util.Objects.requireNonNull;

@Immutable
public class SubPlan
{
    private final PlanFragment fragment;
    private final List<SubPlan> children;

    public SubPlan(PlanFragment fragment, List<SubPlan> children)
    {
        requireNonNull(fragment, "fragment is null");
        requireNonNull(children, "children is null");

        this.fragment = fragment;
        this.children = ImmutableList.copyOf(children);
    }

    public PlanFragment getFragment()
    {
        return fragment;
    }

    public List<SubPlan> getChildren()
    {
        return children;
    }

    /**
     * Flattens the subplan and returns all PlanFragments in the tree
     */
    public List<PlanFragment> getAllFragments()
    {
        ImmutableList.Builder<PlanFragment> fragments = ImmutableList.builder();

        fragments.add(getFragment());
        for (SubPlan child : getChildren()) {
            fragments.addAll(child.getAllFragments());
        }

        return fragments.build();
    }

    public SubPlan withBucketToPartition(Optional<int[]> bucketToPartition)
    {
        return new SubPlan(fragment.withBucketToPartition(bucketToPartition), children);
    }

    public void sanityCheck()
    {
        Multiset<PlanFragmentId> remoteSourceIds = fragment.getRemoteSourceNodes().stream()
                .map(RemoteSourceNode::getSourceFragmentIds)
                .flatMap(List::stream)
                .collect(toImmutableMultiset());

        Multiset<PlanFragmentId> childrenIds = children.stream()
                .map(SubPlan::getFragment)
                .map(PlanFragment::getId)
                .collect(toImmutableMultiset());

        checkState(childrenIds.containsAll(remoteSourceIds), "child fragments must include all remote source fragments (%s vs %s)", remoteSourceIds, childrenIds);
        long tableWriteCount = stream(Traverser.forTree(PlanNode::getSources).depthFirstPreOrder(fragment.getRoot()))
                .filter(TableWriterNode.class::isInstance)
                .count();
        checkState(tableWriteCount <= 1, "Fragment cannot contain more than one TableWriterNode");
        if (tableWriteCount == 1) {
            if (!(fragment.getRoot() instanceof OutputNode || fragment.getRoot() instanceof TableWriterNode)) {
                // Root can be OutputNode when forceSingleNode is enabled.
                // In that case the whole plan has single fragment.
                throw new VerifyException("For a fragment contains TableWriteNode, the root has to be either OutputNode or TableWriterNode");
            }
        }

        for (SubPlan child : children) {
            child.sanityCheck();
        }
    }
}
