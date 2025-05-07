import java.io.IOException;
import java.util.*;

public class ChatRoom {

    private String chatRoomName;
    private final Set<Server.ConnectionHandler> currentParticipants = new HashSet<>();
    private List<String> previousMessages;

    public ChatRoom(String chatRoomName) {
        this.chatRoomName = chatRoomName;
        this.previousMessages = new ArrayList<>();
    }

    public String getChatRoomName() {
        return chatRoomName;
    }

    public void addUserToChatRoom(Server.ConnectionHandler user) {
        try {
            currentParticipants.add(user);
            user.sendMessage("You have joined the chat room: " + chatRoomName);
            sendPreviousMessages(user);
            // Broadcast to other users (excluding the one who just joined)
            for (Server.ConnectionHandler participant : currentParticipants) {
                if (participant != user) {
                    participant.sendMessage("-- User " + user.getClientUserName() + " joined the chat room :) --");
                }
            }
            // Add the join message to chat history
            previousMessages.add("-- User " + user.getClientUserName() + " joined the chat room :) --");
        }
        catch (Exception e) {
            System.err.println("Error adding user to chat room: " + e.getMessage());
        }
    }

    public void removeUserFromChatRoom(Server.ConnectionHandler user) {
        try {
            currentParticipants.remove(user);
            // Only broadcast if there are still people in the room
            if (!currentParticipants.isEmpty()) {
                String leaveMessage = "-- User " + user.getClientUserName() + " left the chat room --";
                broadcastMessage(leaveMessage);
                previousMessages.add(leaveMessage);
            }
        }
        catch (Exception e) {
            System.err.println("Error removing user from chat room: " + e.getMessage());
        }
    }

    public void broadcastMessage(String message) {
        for (Server.ConnectionHandler user : currentParticipants) {
            if (user != null) {
                user.sendMessage(message);
            }
        }
        // Only store non-command messages in history
        if (!message.startsWith("/")) {
            previousMessages.add(message);
        }
    }

    public void sendPreviousMessages(Server.ConnectionHandler user) {
        try {
            if (user != null && previousMessages.isEmpty() == false) {
                user.sendMessage("--- Previous messages ---");
                for (String message : previousMessages) {
                    user.sendMessage(message);
                }
                user.sendMessage("--- End of History ---");
            }
        } catch (Exception e) {
            System.err.println("Error sending previous messages: " + e.getMessage());
        }
    }

    public int getParticipantCount() {
        return currentParticipants.size();
    }
}