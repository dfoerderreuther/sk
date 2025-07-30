# Workflow Approval Dialog Participant Step

This component provides a comprehensive Dialog Participant Step for AEM workflows that displays payload items as clickable links and includes approval functionality.

## Features

- **Payload Item Display**: Automatically fetches and displays workflow payload items
- **Clickable Links**: Converts payload items to clickable links for both Author and Publish environments
- **Rich UI**: Uses Coral UI components for a consistent AEM experience
- **Multiple Resource Types**: Supports pages, assets, folders, and other resources
- **Approval Workflow**: Includes decision fields, comments, and priority settings
- **Responsive Design**: Works well on both desktop and mobile devices

## Components

### Dialog Structure (`_cq_dialog/.content.xml`)
- **Payload Items Tab**: Displays workflow payload items with links
- **Approval Tab**: Contains decision fields, comments, and priority settings

### JavaScript Functionality (`js/approval-dialog.js`)
The `WorkflowApprovalDialog` object provides:
- Automatic payload item loading
- Link generation for Author and Publish environments
- Error handling and loading states
- Resource type detection and appropriate icon display

### CSS Styling (`css/approval-dialog.css`)
- Professional styling for payload items
- Hover effects and transitions
- Responsive design for mobile devices
- Color-coded icons for different resource types

## Usage in Workflow Models

1. **Add Dialog Participant Step**: In your workflow model, add a Dialog Participant Step
2. **Configure Dialog**: Set the dialog path to `/apps/skipton/workflow/dialogs/approval`
3. **Assign Participants**: Configure the participant assignment (users, groups, etc.)
4. **Deploy**: Deploy the workflow model and activate it

## JavaScript API

The component exposes a global `WorkflowApprovalDialog` object with these methods:

- `init()`: Initialize the dialog functionality
- `loadPayloadItems()`: Load and display payload items
- `getWorkflowContext()`: Get workflow context from URL or page data
- `fetchPayloadItems(payloadPath)`: Fetch items from a specific payload path
- `renderPayloadItems(items, container)`: Render items as clickable links

## Configuration

### URL Patterns
The component automatically detects workflow context from:
- URL parameters: `workflowId` or `item`
- Granite author selection API
- Current page context

### Link Generation
- **Author links**: `/editor.html{path}.html` for pages, `/assets.html{folder}` for assets
- **Publish links**: Replaces 'author' with 'publish' in hostname

## Customization

### Adding Custom Resource Types
Edit `getIconForType()` in `approval-dialog.js`:

```javascript
getIconForType: function(type) {
    switch (type) {
        case 'my:CustomType':
            return 'custom-icon';
        // ... existing cases
    }
}
```

### Modifying Link Generation
Edit `getAuthorUrl()` and `getPublishUrl()` methods to match your environment setup.

### Custom Styling
Add custom CSS to `css/approval-dialog.css` or create additional stylesheets.

## Browser Support

- Chrome 70+
- Firefox 65+
- Safari 12+
- Edge 79+

## Dependencies

- AEM 6.5+ or AEM as a Cloud Service
- Coral UI Foundation components
- jQuery (included in AEM)

## Security Considerations

- All user input is properly escaped to prevent XSS attacks
- Links are validated before generation
- AJAX requests follow AEM's standard security model 