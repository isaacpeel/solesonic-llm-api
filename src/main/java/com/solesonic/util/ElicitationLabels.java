package com.solesonic.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders an elicitation answer the way the user picked it, not the value the tool or agent
 * receives: for each field, the matching {@code oneOf}/{@code enum} entry's label when the schema
 * names one, the raw value otherwise. Shared by chat elicitations and shared-session prompts, so the
 * two show an answer the same way.
 */
public final class ElicitationLabels {
    private static final String ONE_OF = "oneOf";
    private static final String CONST = "const";
    private static final String TITLE = "title";
    private static final String ENUM = "enum";
    private static final String ENUM_NAMES = "enumNames";

    private ElicitationLabels() {
    }

    /**
     * Multiple fields join with {@code ", "}; a single field is its label alone. Null when nothing
     * in {@code content} has a label.
     *
     * @param properties the requested schema's {@code properties}, by field name
     */
    public static String summarize(Map<String, Object> properties, Map<String, Object> content) {
        if (content.isEmpty()) {
            return null;
        }

        List<String> labels = new ArrayList<>();

        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String label = label(properties.get(entry.getKey()), entry.getValue());

            if (label != null) {
                labels.add(label);
            }
        }

        if (labels.isEmpty()) {
            return null;
        }

        return String.join(", ", labels);
    }

    /**
     * The display label for one field's submitted value, resolved against that field's own JSON
     * Schema fragment: a {@code oneOf} list of {@code {const, title}} entries first, then a parallel
     * {@code enum}/{@code enumNames} pair, falling back to the raw value when the schema names no
     * label for it (a free-text field, or a value that matches no listed option).
     */
    public static String label(Object propertySchema, Object value) {
        if (value == null) {
            return null;
        }

        if (propertySchema instanceof Map<?, ?> schema) {
            if (schema.get(ONE_OF) instanceof List<?> oneOf) {
                for (Object candidate : oneOf) {
                    if (candidate instanceof Map<?, ?> entry
                            && String.valueOf(value).equals(String.valueOf(entry.get(CONST)))
                            && entry.get(TITLE) instanceof String title) {
                        return title;
                    }
                }
            }

            if (schema.get(ENUM) instanceof List<?> enumValues && schema.get(ENUM_NAMES) instanceof List<?> enumNames) {
                for (int index = 0; index < enumValues.size() && index < enumNames.size(); index++) {
                    if (String.valueOf(value).equals(String.valueOf(enumValues.get(index)))
                            && enumNames.get(index) instanceof String enumName) {
                        return enumName;
                    }
                }
            }
        }

        return String.valueOf(value);
    }
}
