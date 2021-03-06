package qa.qcri.aidr.predictui.facade;

import qa.qcri.aidr.dbmanager.dto.CustomUiTemplateDTO;

import javax.ejb.Local;

import java.util.List;


@Local
public interface CustomUITemplateFacade {
    public List<CustomUiTemplateDTO> getAllCustomUITemplateByCrisisID(long crisisID);
    public List<CustomUiTemplateDTO> getCustomUITemplateBasedOnTypeByCrisisID(long crisisID, int type);
    public List<CustomUiTemplateDTO> getCustomUITemplateBasedOnTypeByCrisisIDAndAttributeID(long crisisID, long attributeID,int type);
    public List<CustomUiTemplateDTO> getCustomUITemplateByCrisisIDAndAttributeID(long crisisID, long attributeID);
    public CustomUiTemplateDTO addCustomUITemplate(CustomUiTemplateDTO customUITemplate);
    public CustomUiTemplateDTO updateCustomUITemplate(CustomUiTemplateDTO currentTemplate, CustomUiTemplateDTO updatedTemplate);
    public CustomUiTemplateDTO updateCustomUITemplateStatus(CustomUiTemplateDTO currentTemplate, CustomUiTemplateDTO updatedTemplate);
    public void deleteCustomUITemplateBasedOnTypeByCrisisID(long crisisID, int type);
    public void deleteCustomUITemplateByCrisisID(long crisisID);

}
