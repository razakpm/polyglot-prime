package org.techbd.service.converters.shinny;

import org.hl7.fhir.r4.model.ResourceType;
import org.springframework.stereotype.Component;

@Component
public class PractitionerConverter  extends BaseConverter {

    @Override
    public ResourceType getResourceType() {
       return ResourceType.Practitioner;
    }
}
