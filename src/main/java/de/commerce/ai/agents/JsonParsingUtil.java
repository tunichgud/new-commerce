package de.commerce.ai.agents;

/**
 * Utility methods for cleaning LLM responses before JSON parsing.
 */
final class JsonParsingUtil {

    private JsonParsingUtil() {
    }

    /**
     * Strips a Markdown code-block wrapper from an LLM response.
     * Handles both ```json ... ``` and plain ``` ... ``` fences.
     * Returns the trimmed inner content, or the original string if no fence is found.
     */
    static String stripMarkdownCodeBlock(String response) {
        if (response == null) {
            return null;
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline == -1) {
                return trimmed;
            }
            String withoutOpenFence = trimmed.substring(firstNewline + 1);
            if (withoutOpenFence.endsWith("```")) {
                withoutOpenFence = withoutOpenFence.substring(0, withoutOpenFence.length() - 3);
            }
            return withoutOpenFence.trim();
        }
        return trimmed;
    }
}
