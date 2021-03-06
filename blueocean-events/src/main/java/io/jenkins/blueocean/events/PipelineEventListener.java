package io.jenkins.blueocean.events;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.Run;
import io.jenkins.blueocean.rest.impl.pipeline.PipelineInputStepListener;
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeUtil;
import org.jenkinsci.plugins.pubsub.Events;
import org.jenkinsci.plugins.pubsub.Message;
import org.jenkinsci.plugins.pubsub.MessageException;
import org.jenkinsci.plugins.pubsub.PubsubBus;
import org.jenkinsci.plugins.pubsub.RunMessage;
import org.jenkinsci.plugins.pubsub.SimpleMessage;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;


/**
 * Listen for run events, filter and publish stage/branch events
 * to notify the UX that there are changes when viewing the
 * pipeline results screen.
 *
 * This may be useful (or may not be needed) for live-ness of the pipeline results screen.
 */
@Extension
public class PipelineEventListener implements GraphListener {

    private static final Logger LOGGER = Logger.getLogger(PipelineEventListener.class.getName());

    private final Map<FlowExecution, String> currentStageName = new WeakHashMap<>();
    private final Map<FlowExecution, String> currentStageId = new WeakHashMap<>();

    @Override
    public void onNewHead(FlowNode flowNode) {
        // test whether we have a stage node
        if (PipelineNodeUtil.isStage(flowNode)) {
            List<String> branch = getBranch(flowNode);
            currentStageName.put(flowNode.getExecution(), flowNode.getDisplayName());
            currentStageId.put(flowNode.getExecution(), flowNode.getId());
            publishEvent(newMessage(PipelineEventChannel.Event.pipeline_stage, flowNode, branch));
        } else if (flowNode instanceof StepStartNode) {
            if (flowNode.getAction(BodyInvocationAction.class) != null) {
                List<String> branch = getBranch(flowNode);
                branch.add(flowNode.getId());
                publishEvent(newMessage(PipelineEventChannel.Event.pipeline_block_start, flowNode, branch));
            }
        } else if (flowNode instanceof StepAtomNode) {
            List<String> branch = getBranch(flowNode);
            publishEvent(newMessage(PipelineEventChannel.Event.pipeline_step, flowNode, branch));
        } else if (flowNode instanceof StepEndNode) {
            if (flowNode.getAction(BodyInvocationAction.class) != null) {
                FlowNode startNode = ((StepEndNode) flowNode).getStartNode();
                String startNodeId = startNode.getId();

                List<String> branch = getBranch(startNode);

                branch.add(startNodeId);
                publishEvent(newMessage(PipelineEventChannel.Event.pipeline_block_end, flowNode, branch));
            }
        } else if (flowNode instanceof FlowEndNode) {
            publishEvent(newMessage(PipelineEventChannel.Event.pipeline_end, flowNode.getExecution()));
        }
    }

    private List<String> getBranch(FlowNode flowNode) {
        List<String> branch = new ArrayList<>();
        FlowNode parentBlock = getParentBlock(flowNode);

        while (parentBlock != null) {
            branch.add(0, parentBlock.getId());
            parentBlock = getParentBlock(parentBlock);
        }

        return branch;
    }

    private FlowNode getParentBlock(FlowNode flowNode) {
        List<FlowNode> parents = flowNode.getParents();

        for (FlowNode parent : parents) {
            if (parent instanceof StepStartNode) {
                if (parent.getAction(BodyInvocationAction.class) != null) {
                    return parent;
                }
            }
        }

        for (FlowNode parent : parents) {
            if (parent instanceof StepEndNode) {
                continue;
            }
            FlowNode grandparent = getParentBlock(parent);
            if (grandparent != null) {
                return grandparent;
            }
        }

        return null;
    }

    private String toPath(List<String> branch) {
        StringBuilder builder = new StringBuilder();
        for (String leaf : branch) {
            if(builder.length() > 0) {
                builder.append("/");
            }
            builder.append(leaf);
        }
        return builder.toString();
    }

    private static @CheckForNull Run<?, ?> runFor(FlowExecution exec) {
        Queue.Executable executable;
        try {
            executable = exec.getOwner().getExecutable();
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
            return null;
        }
        if (executable instanceof Run) {
            return (Run<?, ?>) executable;
        } else {
            return null;
        }
    }

    private static Message newMessage(PipelineEventChannel.Event event, FlowExecution exec) {
        SimpleMessage message = new SimpleMessage()
                .setChannelName(PipelineEventChannel.NAME)
                .setEventName(event);
        Run<?, ?> run = runFor(exec);
        if (run != null) {
            message.set(PipelineEventChannel.EventProps.pipeline_job_name, run.getParent().getFullName())
                   .set(PipelineEventChannel.EventProps.pipeline_run_id, run.getId());
        }
        return message;
    }

    private Message newMessage(PipelineEventChannel.Event event, FlowNode flowNode, List<String> branch) {
        Message message = newMessage(event, flowNode.getExecution());

        message.set(PipelineEventChannel.EventProps.pipeline_step_flownode_id, flowNode.getId());
        message.set(PipelineEventChannel.EventProps.pipeline_context, toPath(branch));
        if (currentStageName != null) {
            message.set(PipelineEventChannel.EventProps.pipeline_step_stage_name, currentStageName.get(flowNode.getExecution()));
            message.set(PipelineEventChannel.EventProps.pipeline_step_stage_id, currentStageId.get(flowNode.getExecution()));
        }
        if (flowNode instanceof StepNode) {
            StepNode stepNode = (StepNode) flowNode;
            StepDescriptor stepDescriptor = stepNode.getDescriptor();
            if(stepDescriptor != null) {
                message.set(PipelineEventChannel.EventProps.pipeline_step_name, stepDescriptor.getFunctionName());
            }
        }

        if (flowNode instanceof StepAtomNode) {
            Run<?, ?> run = runFor(flowNode.getExecution());
            if (run != null) {
                boolean pausedForInputStep = PipelineNodeUtil
                    .isPausedForInputStep((StepAtomNode) flowNode, run.getAction(InputAction.class));
                if (pausedForInputStep) {
                    // Fire job event to tell we are paused
                    // We will publish on the job channel
                    try {
                        PubsubBus.getBus().publish(new RunMessage(run)
                            .setEventName(Events.JobChannel.job_run_paused)
                        );
                    } catch (MessageException e) {
                        LOGGER.log(Level.WARNING, "Error publishing Run pause event.", e);
                    }
                }
                message.set(PipelineEventChannel.EventProps.pipeline_step_is_paused, String.valueOf(pausedForInputStep));
            }
        }
        return message;
    }

    private static void publishEvent(Message message) {
        try {
            PubsubBus.getBus().publish(message);
        } catch (MessageException e) {
            LOGGER.log(Level.SEVERE, "Unexpected error publishing pipeline FlowNode event.", e);
        }
    }

    @Extension
    public static class StartPublisher extends FlowExecutionListener {

        @Override
        public void onRunning(FlowExecution execution) {
            publishEvent(newMessage(PipelineEventChannel.Event.pipeline_start, execution));
        }

    }

    @Extension
    public static class InputStepPublisher implements PipelineInputStepListener {

        @Override
        public void onStepContinue(InputStep inputStep, WorkflowRun run) {
            // fire an unpaused event in case the input step has received its input
            try {
                PubsubBus.getBus().publish(new RunMessage(run)
                        .setEventName(Events.JobChannel.job_run_unpaused)
                );
            } catch (MessageException e) {
                LOGGER.log(Level.WARNING, "Error publishing Run un-pause event.", e);
            }
        }
    }

}
