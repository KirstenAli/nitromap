package dev.nitromap.query;

import java.util.ArrayList;
import java.util.List;

final class Tokenizer {

    private final String input;
    private int position;

    Tokenizer(String input) {
        this.input = input;
    }

    List<String> tokenize() {
        List<String> tokens = new ArrayList<>();
        while (hasMore()) {
            skipSpaces();
            if (hasMore()) tokens.add(next());
        }
        return tokens;
    }

    private String next() {
        char value = input.charAt(position);
        if (value == '\'') return quoted();
        if (isOperator(value)) return operator();
        if (isSymbol(value)) return Character.toString(input.charAt(position++));
        return word();
    }

    private String quoted() {
        StringBuilder token = new StringBuilder("'");
        position++;
        while (hasMore()) {
            char value = input.charAt(position++);
            if (value != '\'') token.append(value);
            else if (hasMore() && input.charAt(position) == '\'') {
                token.append("''");
                position++;
            }
            else return token.append('\'').toString();
        }
        throw new IllegalArgumentException("Unclosed string literal");
    }

    private String operator() {
        String token = Character.toString(input.charAt(position++));
        if (hasMore() && joinsOperator(token, input.charAt(position))) token += input.charAt(position++);
        return token;
    }

    private String word() {
        int start = position;
        while (hasMore() && !boundary(input.charAt(position))) position++;
        return input.substring(start, position);
    }

    private void skipSpaces() {
        while (hasMore() && Character.isWhitespace(input.charAt(position))) position++;
    }

    private boolean hasMore() {
        return position < input.length();
    }

    private boolean boundary(char value) {
        return Character.isWhitespace(value) || isSymbol(value) || isOperator(value) || value == '\'';
    }

    private boolean isSymbol(char value) {
        return ",()*;".indexOf(value) >= 0;
    }

    private boolean isOperator(char value) {
        return "=<>!".indexOf(value) >= 0;
    }

    private boolean joinsOperator(String first, char second) {
        return second == '=' || (first.equals("<") && second == '>');
    }
}
