package com.samvaad.tui.cli;

/**
 * Raw command-line option values, before prompting and validation.
 *
 * @param serverUrl value of {@code --server}, may be null when not provided
 * @param username value of {@code --username}, may be null when not provided
 */
public record CliOptions(String serverUrl, String username) {
}
