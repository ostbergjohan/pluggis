package se.ostberg.pluggis.student;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Talar om för Hibernate vilken elev (student_key) frågor och nya rader gäller. */
@Component
public class StudentTenantResolver implements CurrentTenantIdentifierResolver<String>, HibernatePropertiesCustomizer {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return String.valueOf(StudentContext.currentOrDefault());
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
