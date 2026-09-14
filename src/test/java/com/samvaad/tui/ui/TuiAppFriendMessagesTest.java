package com.samvaad.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.samvaad.tui.api.SamvaadApiException;
import org.junit.jupiter.api.Test;

class TuiAppFriendMessagesTest {

    @Test
    void lookupMessagesByStatus() {
        assertEquals("User not found.",
                TuiApp.friendLookupMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.HTTP_ERROR, 404, "gone")));
        assertEquals("Invalid username.",
                TuiApp.friendLookupMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.HTTP_ERROR, 400, "bad")));
        assertEquals("Session expired. Restart and log in again.",
                TuiApp.friendLookupMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.AUTHENTICATION_FAILED, 401, "expired")));
        assertEquals("Cannot reach server.",
                TuiApp.friendLookupMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.SERVER_UNAVAILABLE, -1, "down")));
    }

    @Test
    void sendMessagesByStatus() {
        assertEquals("Cannot send a friend request to yourself.",
                TuiApp.friendSendMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.HTTP_ERROR, 403, "forbidden")));
        assertEquals("Friend request already pending or already friends.",
                TuiApp.friendSendMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.HTTP_ERROR, 409, "conflict")));
        assertEquals("User not found.",
                TuiApp.friendSendMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.HTTP_ERROR, 404, "gone")));
    }

    @Test
    void mutationMessagesByStatus() {
        SamvaadApiException forbidden = new SamvaadApiException(
                SamvaadApiException.Kind.HTTP_ERROR, 403, "forbidden");
        assertEquals("Accept not allowed for this request.",
                TuiApp.friendMutationMessage(forbidden, "Accept"));
        assertEquals("Cancel not allowed for this request.",
                TuiApp.friendMutationMessage(forbidden, "Cancel"));

        SamvaadApiException gone = new SamvaadApiException(
                SamvaadApiException.Kind.HTTP_ERROR, 404, "gone");
        assertEquals("Friend request not found.",
                TuiApp.friendMutationMessage(gone, "Reject"));

        SamvaadApiException conflict = new SamvaadApiException(
                SamvaadApiException.Kind.HTTP_ERROR, 409, "conflict");
        assertEquals("Friend request is no longer pending.",
                TuiApp.friendMutationMessage(conflict, "Accept"));
    }

    @Test
    void listMessageFallsBackToStatusCode() {
        assertEquals("Could not load friend requests (HTTP 500).",
                TuiApp.friendListMessage(new SamvaadApiException(
                        SamvaadApiException.Kind.HTTP_ERROR, 500, "boom")));
    }

    @Test
    void viewAndSearchStateDefaults() {
        TuiState state = new TuiState();

        assertEquals(TuiState.View.CHAT, state.view());
        assertEquals(TuiState.SearchFocus.INPUT, state.searchFocus());
        assertEquals("", state.searchInput());
        assertEquals(TuiState.RequestSection.INCOMING, state.requestSection());
        assertEquals(0, state.requestSelectedIndex());
    }
}
