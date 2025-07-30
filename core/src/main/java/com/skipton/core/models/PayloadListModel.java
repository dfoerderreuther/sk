/*
 *  Copyright 2015 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.skipton.core.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;

import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.WorkflowException;
import com.day.cq.wcm.api.Page;
import com.day.cq.dam.api.Asset;

import javax.annotation.PostConstruct;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.List;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Model(adaptables = {Resource.class, SlingHttpServletRequest.class})
public class PayloadListModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadListModel.class);

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL)
    @Default(values = "Workflow Payload Items")
    private String title;

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL)
    @Default(values = "")
    private String description;

    @ValueMapValue(injectionStrategy = InjectionStrategy.OPTIONAL)
    @Default(booleanValues = true)
    private Boolean showDetails;

    @SlingObject
    private Resource currentResource;
    
    @SlingObject
    private ResourceResolver resourceResolver;

    @SlingObject(injectionStrategy = InjectionStrategy.OPTIONAL)
    private SlingHttpServletRequest request;

    private List<PayloadItem> payloadItems;
    private String workflowId;
    private String workflowTitle;
    private String payloadPath;

    @PostConstruct
    protected void init() {
        payloadItems = new ArrayList<>();
        loadWorkflowPayloadItems();
    }

    private void loadWorkflowPayloadItems() {
        try {
            // Try to get workflow information from the request context
            WorkflowContext workflowContext = getWorkflowContext();
            
            if (workflowContext != null) {
                this.workflowId = workflowContext.getWorkflowId();
                this.workflowTitle = workflowContext.getWorkflowTitle();
                this.payloadPath = workflowContext.getPayloadPath();
                
                LOGGER.info("Loading payload items for workflow: {} at path: {}", workflowId, payloadPath);
                if (payloadPath.startsWith("/var/workflow/packages/")) {
                    List<String> packagePayloadItems = getPackagePayloadItems(payloadPath);
                    packagePayloadItems.forEach(this::loadPayloadItemsFromPath);
                } else {
                    loadPayloadItemsFromPath(payloadPath);
                }
                
            } else {
                LOGGER.warn("No workflow context found, using fallback payload items");
                loadFallbackPayloadItems();
            }
        } catch (Exception e) {
            LOGGER.error("Error loading workflow payload items", e);
            loadFallbackPayloadItems();
        }
    }

    private List<String> getPackagePayloadItems(String payloadPath) {
        List<String> packagePayloadItems = new ArrayList<>();
        
        try {
            // Get JCR Session from ResourceResolver
            Session session = resourceResolver.adaptTo(Session.class);
            if (session == null) {
                LOGGER.error("Could not adapt ResourceResolver to JCR Session");
                return packagePayloadItems;
            }
            
            if (!session.nodeExists(payloadPath)) {
                LOGGER.warn("Package node does not exist at path: {}", payloadPath);
                return packagePayloadItems;
            }
            
            Node packageNode = session.getNode(payloadPath);
            
            if (!packageNode.hasNode("jcr:content/vlt:definition") && !packageNode.hasNode("definition")) {
                LOGGER.warn("Package node does not have definition node at: {}", payloadPath);
                return packagePayloadItems;
            }
            
            // Try both possible paths for package definition
            Node definitionNode = null;
            if (packageNode.hasNode("jcr:content/vlt:definition")) {
                definitionNode = packageNode.getNode("jcr:content/vlt:definition");
            } else if (packageNode.hasNode("definition")) {
                definitionNode = packageNode.getNode("definition");
            }
            
            if (definitionNode != null && definitionNode.hasNode("filter")) {
                Node filtersNode = definitionNode.getNode("filter");
                NodeIterator filterIterator = filtersNode.getNodes();
                
                while (filterIterator.hasNext()) {
                    Node filterNode = filterIterator.nextNode();
                    if (filterNode.hasProperty("root")) {
                        String filterRoot = filterNode.getProperty("root").getString();
                        packagePayloadItems.add(filterRoot);
                    }
                }
                LOGGER.info("Extracted {} filter roots from package: {}", packagePayloadItems.size(), payloadPath);
            } else {
                LOGGER.warn("No filter node found in definition at: {}", payloadPath);
            }
            
        } catch (RepositoryException e) {
            LOGGER.error("Error reading package payload items from path: " + payloadPath, e);
        }

        return packagePayloadItems;
    }

    /**
     * Utility method to inspect package structure for debugging
     */
    private void inspectPackageStructure(String payloadPath) {
        try {
            Session session = resourceResolver.adaptTo(Session.class);
            if (session != null && session.nodeExists(payloadPath)) {
                Node packageNode = session.getNode(payloadPath);
                
                if (packageNode.hasNode("jcr:content/vlt:definition/filter")) {
                    Node filter = packageNode.getNode("jcr:content/vlt:definition/filter");
                    NodeIterator filters = filter.getNodes();
                    int filterCount = 0;
                    while (filters.hasNext()) {
                        filters.nextNode();
                        filterCount++;
                    }
                    LOGGER.debug("Package contains {} filter definitions", filterCount);
                }
            }
        } catch (RepositoryException e) {
            LOGGER.error("Error inspecting package structure", e);
        }
    }

    private WorkflowContext getWorkflowContext() {
        if (request == null) {
            LOGGER.warn("No request available to extract workflow context");
            return null;
        }

        try {
            // Method 1: Try to get WorkItem from various request attributes
            String[] workItemAttributeNames = {"workItem", "item", "workflowItem", "granite.workflow.item"};
            for (String attributeName : workItemAttributeNames) {
                Object workflowItem = request.getAttribute(attributeName);
                if (workflowItem instanceof WorkItem) {
                    WorkItem item = (WorkItem) workflowItem;
                    LOGGER.info("Found WorkItem via attribute '{}' - Workflow ID: {}", 
                               attributeName, item.getWorkflow().getId());
                    return new WorkflowContext(
                        item.getWorkflow().getId(),
                        item.getWorkflow().getWorkflowModel().getTitle(),
                        item.getWorkflowData().getPayload().toString()
                    );
                }
            }

            // Method 2: Try to get workflow item ID from request parameters
            String[] parameterNames = {"item", "workflowId", "workItemId", "wfItem"};
            for (String paramName : parameterNames) {
                String itemId = request.getParameter(paramName);
                if (itemId != null && !itemId.isEmpty()) {
                    LOGGER.debug("Found workflow item ID from parameter '{}': {}", paramName, itemId);
                    WorkflowContext context = getWorkflowContextFromItemId(itemId);
                    if (context != null) {
                        return context;
                    }
                }
            }

            // Method 3: Try to extract from request suffix
            String suffix = request.getRequestPathInfo().getSuffix();
            
            if (suffix != null && suffix.contains("/var/workflow/instances/")) {
                String[] parts = suffix.split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("instances".equals(parts[i]) && i + 1 < parts.length) {
                        String workflowId = parts[i + 1];
                        LOGGER.debug("Extracted workflow ID from suffix: {}", workflowId);
                        WorkflowContext context = getWorkflowContextFromWorkflowId(workflowId);
                        if (context != null) {
                            return context;
                        }
                    }
                }
            }

            // Method 4: Try to get workflow session and look for active workflows
            WorkflowSession workflowSession = resourceResolver.adaptTo(WorkflowSession.class);
            if (workflowSession == null) {
                LOGGER.warn("Could not get WorkflowSession");
            }

            LOGGER.warn("No workflow context found in request");
            return null;

        } catch (Exception e) {
            LOGGER.error("Error extracting workflow context", e);
            return null;
        }
    }

    private WorkflowContext getWorkflowContextFromItemId(String itemId) {
        try {
            WorkflowSession workflowSession = resourceResolver.adaptTo(WorkflowSession.class);
            if (workflowSession == null) {
                LOGGER.error("Could not get WorkflowSession");
                return null;
            }

            // First try exact match by work item ID
            WorkItem[] workItems = workflowSession.getActiveWorkItems();
            for (WorkItem item : workItems) {
                if (item.getId().equals(itemId)) {
                    LOGGER.debug("Found exact work item match - Workflow ID: {}", item.getWorkflow().getId());
                    return new WorkflowContext(
                        item.getWorkflow().getId(),
                        item.getWorkflow().getWorkflowModel().getTitle(),
                        item.getWorkflowData().getPayload().toString()
                    );
                }
            }

            // If exact match fails, extract workflow ID from the item ID path
            String extractedWorkflowId = extractWorkflowIdFromItemId(itemId);
            if (extractedWorkflowId != null) {
                // Now find the workflow with exact ID match
                for (WorkItem item : workItems) {
                    String workflowId = item.getWorkflow().getId();
                    if (workflowId.equals(extractedWorkflowId)) {
                        LOGGER.debug("Found workflow match by extracted ID: {}", workflowId);
                        return new WorkflowContext(
                            item.getWorkflow().getId(),
                            item.getWorkflow().getWorkflowModel().getTitle(),
                            item.getWorkflowData().getPayload().toString()
                        );
                    }
                }
                
                LOGGER.warn("No active workflow found with extracted ID: {}", extractedWorkflowId);
            }

            LOGGER.warn("Could not find matching workflow for item ID: {}", itemId);
            return null;

        } catch (WorkflowException e) {
            LOGGER.error("Error getting workflow context from item ID: " + itemId, e);
            return null;
        }
    }

    private String extractWorkflowIdFromItemId(String itemId) {
        if (itemId == null || !itemId.contains("/var/workflow/instances/")) {
            return null;
        }
        
        try {
            // Find the workItems part and remove it to get the workflow ID
            int workItemsIndex = itemId.indexOf("/workItems/");
            if (workItemsIndex > 0) {
                return itemId.substring(0, workItemsIndex);
            }
            
            // Fallback: try to extract workflow ID pattern from the path
            String[] parts = itemId.split("/");
            StringBuilder workflowIdBuilder = new StringBuilder();
            boolean foundInstances = false;
            
            for (int i = 0; i < parts.length; i++) {
                if ("instances".equals(parts[i])) {
                    foundInstances = true;
                }
                
                if (foundInstances) {
                    if (parts[i].equals("workItems")) {
                        break; // Stop before workItems
                    }
                    if (workflowIdBuilder.length() > 0) {
                        workflowIdBuilder.append("/");
                    }
                    workflowIdBuilder.append(parts[i]);
                }
            }
            
            if (foundInstances && workflowIdBuilder.length() > 0) {
                return "/" + workflowIdBuilder.toString().replaceFirst("^instances/", "var/workflow/instances/");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error extracting workflow ID from: " + itemId, e);
        }
        
        return null;
    }

    private WorkflowContext getWorkflowContextFromWorkflowId(String workflowId) {
        try {
            WorkflowSession workflowSession = resourceResolver.adaptTo(WorkflowSession.class);
            if (workflowSession == null) {
                LOGGER.error("Could not get WorkflowSession");
                return null;
            }

            // Try to get the workflow by ID
            Workflow workflow = workflowSession.getWorkflow(workflowId);
            if (workflow != null) {
                return new WorkflowContext(
                    workflow.getId(),
                    workflow.getWorkflowModel().getTitle(),
                    workflow.getWorkflowData().getPayload().toString()
                );
            }

            // If direct lookup fails, try to find it in active workflows
            WorkItem[] workItems = workflowSession.getActiveWorkItems();
            for (WorkItem item : workItems) {
                if (item.getWorkflow().getId().equals(workflowId)) {
                    return new WorkflowContext(
                        item.getWorkflow().getId(),
                        item.getWorkflow().getWorkflowModel().getTitle(),
                        item.getWorkflowData().getPayload().toString()
                    );
                }
            }

            LOGGER.warn("Could not find workflow with ID: {}", workflowId);
            return null;

        } catch (WorkflowException e) {
            LOGGER.error("Error getting workflow context from workflow ID: " + workflowId, e);
            return null;
        }
    }

    private void loadPayloadItemsFromPath(String payloadPath) {
        if (payloadPath == null || payloadPath.isEmpty()) {
            LOGGER.warn("No payload path provided");
            return;
        }

        Resource payloadResource = resourceResolver.getResource(payloadPath);
        if (payloadResource == null) {
            LOGGER.warn("Could not find resource at payload path: {}", payloadPath);
            return;
        }

        // Determine the type of payload and load accordingly
        if (payloadResource.isResourceType("cq:Page")) {
            loadPagePayload(payloadResource);
        } else if (payloadResource.isResourceType("dam:Asset")) {
            loadAssetPayload(payloadResource);
        } else if (payloadResource.isResourceType("sling:Folder") || 
                   payloadResource.isResourceType("sling:OrderedFolder")) {
            loadFolderPayload(payloadResource);
        } else {
            // Generic resource
            loadGenericPayload(payloadResource);
        }
    }

    private void loadPagePayload(Resource pageResource) {
        Page page = pageResource.adaptTo(Page.class);
        if (page != null) {
            payloadItems.add(new PayloadItem(
                page.getPath(),
                page.getTitle() != null ? page.getTitle() : page.getName(),
                "cq:Page",
                "Page"
            ));
            
            // Optionally load child pages if this is a site structure workflow
            // for (Iterator<Page> children = page.listChildren(); children.hasNext();) {
            //     Page child = children.next();
            //     payloadItems.add(new PayloadItem(
            //         child.getPath(),
            //         child.getTitle() != null ? child.getTitle() : child.getName(),
            //         "cq:Page",
            //         "Child Page"
            //     ));
            // }
        }
    }

    private void loadAssetPayload(Resource assetResource) {
        Asset asset = assetResource.adaptTo(Asset.class);
        if (asset != null) {
            String title = asset.getMetadataValue("dc:title");
            if (title == null || title.isEmpty()) {
                title = asset.getName();
            }
            
            payloadItems.add(new PayloadItem(
                asset.getPath(),
                title,
                "dam:Asset",
                asset.getMimeType()
            ));
        }
    }

    private void loadFolderPayload(Resource folderResource) {
        payloadItems.add(new PayloadItem(
            folderResource.getPath(),
            folderResource.getName(),
            folderResource.getResourceType(),
            "Folder"
        ));
        
        // Load immediate children
        for (Resource child : folderResource.getChildren()) {
            if (child.isResourceType("dam:Asset")) {
                loadAssetPayload(child);
            } else if (child.isResourceType("cq:Page")) {
                loadPagePayload(child);
            }
        }
    }

    private void loadGenericPayload(Resource resource) {
        payloadItems.add(new PayloadItem(
            resource.getPath(),
            resource.getName(),
            resource.getResourceType(),
            "Resource"
        ));
    }

    private void loadFallbackPayloadItems() {
        // Fallback implementation for when no workflow context is available
        payloadItems.add(new PayloadItem("/content/df/us/en/page1", "Page 1", "cq:Page", "Page"));
        payloadItems.add(new PayloadItem("/content/dam/df/asset1.jpg", "Asset 1", "dam:Asset", "Image"));
        payloadItems.add(new PayloadItem("/content/df/us/en/page2", "Page 2", "cq:Page", "Page"));
    }

    // Getters
    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getShowDetails() {
        return showDetails;
    }

    public List<PayloadItem> getPayloadItems() {
        return payloadItems;
    }

    public int getPayloadCount() {
        return payloadItems != null ? payloadItems.size() : 0;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowTitle() {
        return workflowTitle;
    }

    public String getPayloadPath() {
        return payloadPath;
    }

    /**
     * Inner class representing workflow context
     */
    private static class WorkflowContext {
        private final String workflowId;
        private final String workflowTitle;
        private final String payloadPath;

        public WorkflowContext(String workflowId, String workflowTitle, String payloadPath) {
            this.workflowId = workflowId;
            this.workflowTitle = workflowTitle;
            this.payloadPath = payloadPath;
        }

        public String getWorkflowId() { return workflowId; }
        public String getWorkflowTitle() { return workflowTitle; }
        public String getPayloadPath() { return payloadPath; }
    }

    /**
     * Inner class representing a payload item
     */
    public static class PayloadItem {
        private String path;
        private String title;
        private String resourceType;
        private String category;

        public PayloadItem(String path, String title, String resourceType, String category) {
            this.path = path;
            this.title = title;
            this.resourceType = resourceType;
            this.category = category;
        }

        public String getPath() {
            return path;
        }

        public String getTitle() {
            return title;
        }

        public String getResourceType() {
            return resourceType;
        }

        public String getCategory() {
            return category;
        }
    }
}
