package com.skipton.core.workflows;

import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    service = WorkflowProcess.class,
    property = {
        "process.label=Approval Decision Processor",
        "process.description=Processes approval dialog decision and sets workflow status"
    }
)
public class ApprovalDecisionProcessor implements WorkflowProcess {
    
    private static final Logger log = LoggerFactory.getLogger(ApprovalDecisionProcessor.class);
    private static final String STATUS = "status";
    private static final String DECISION_PROPERTY = "decision";
    private static final String COMMENTS_PROPERTY = "comments";
    private static final String PRIORITY_PROPERTY = "priority";

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args) {
        try {
            // Get the payload path
            String payloadPath = workItem.getWorkflowData().getPayload().toString();
            ResourceResolver resolver = workflowSession.adaptTo(ResourceResolver.class);
            
            if (resolver == null) {
                log.error("Could not get ResourceResolver from workflow session");
                setWorkflowStatus(workItem, "ERROR", "Could not get ResourceResolver");
                return;
            }

            // Look for the decision in the workflow data first
            MetaDataMap wfData = workItem.getWorkflow().getWorkflowData().getMetaDataMap();
            String decision = wfData.get(DECISION_PROPERTY, String.class);
            String comments = wfData.get(COMMENTS_PROPERTY, String.class);
            String priority = wfData.get(PRIORITY_PROPERTY, String.class);

            // If not found in workflow data, try to get from payload resource
            if (decision == null) {
                Resource resource = resolver.getResource(payloadPath);
                if (resource != null) {
                    ValueMap properties = resource.getValueMap();
                    decision = properties.get("jcr:content/" + DECISION_PROPERTY, String.class);
                    comments = properties.get("jcr:content/" + COMMENTS_PROPERTY, String.class);
                    priority = properties.get("jcr:content/" + PRIORITY_PROPERTY, String.class);
                }
            }

            if (decision == null || decision.isEmpty()) {
                log.warn("No approval decision found for workflow item. Setting status to PENDING.");
                setWorkflowStatus(workItem, "PENDING", "No decision made yet");
                return;
            }

            // Map dialog decision values to status values
            String status = mapDecisionToStatus(decision);
            
            // Set the status and additional information
            setWorkflowStatus(workItem, status, comments);
            
            // Store additional metadata
            if (priority != null && !priority.isEmpty()) {
                wfData.put(PRIORITY_PROPERTY, priority);
            }
            
            log.info("Approval decision processed: decision={}, status={}, comments={}, priority={}", 
                    decision, status, comments, priority);

        } catch (Exception e) {
            log.error("Error in ApprovalDecisionProcessor", e);
            setWorkflowStatus(workItem, "ERROR", "Error processing approval decision: " + e.getMessage());
        }
    }

    private String mapDecisionToStatus(String decision) {
        if (decision == null) {
            return "PENDING";
        }
        
        switch (decision.toUpperCase()) {
            case "APPROVED":
            case "APPROVE":
                return "APPROVED";
            case "DENIED":
            case "REJECT":
                return "DENIED";
            case "REQUEST_CHANGES":
            case "REQUEST CHANGES":
                return "REQUEST_CHANGES";
            default:
                log.warn("Unknown decision value: {}. Defaulting to PENDING.", decision);
                return "PENDING";
        }
    }

    private void setWorkflowStatus(WorkItem workItem, String status, String comment) {
        MetaDataMap wfData = workItem.getWorkflow().getWorkflowData().getMetaDataMap();
        wfData.put(STATUS, status);
        
        if (comment != null && !comment.isEmpty()) {
            wfData.put("comment", comment);
        }
        
        log.info("Workflow status set to: {} with comment: {}", status, comment);
    }
} 