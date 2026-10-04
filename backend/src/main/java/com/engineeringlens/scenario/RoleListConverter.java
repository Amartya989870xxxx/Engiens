package com.engineeringlens.scenario;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a lab's roles as a comma-separated list of names, in the order the user chose them. */
@Converter
public class RoleListConverter implements AttributeConverter<List<ScenarioRole>, String> {

    @Override
    public String convertToDatabaseColumn(List<ScenarioRole> roles) {
        return roles == null ? "" : String.join(",", roles.stream().map(Enum::name).toList());
    }

    @Override
    public List<ScenarioRole> convertToEntityAttribute(String db) {
        if (db == null || db.isBlank()) {
            return List.of();
        }
        return Arrays.stream(db.split(",")).map(ScenarioRole::valueOf).toList();
    }
}
