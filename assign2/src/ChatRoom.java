import java.io.IOException;
import java.util.*;

public class ChatRoom {

    private String chatRoomName;
    private final Set<Server.ConnectionHandler> currentParticipants = new HashSet<>();
    private List<String> previousMessages;

    public ChatRoom(String chatRoomName) {
        this.chatRoomName = chatRoomName;
        this.currentParticipants.clear();
        this.previousMessages = new ArrayList<>();
    }

    public String getChatRoomName() {
        return chatRoomName;
    }

    public void addUserToChatRoom(Server.ConnectionHandler user) {
        try {
            currentParticipants.add(user);
            sendPreviousMessages(user);
            broadcastMessage("-- User " + user.getClientUserName() + " joined the chat room :) --");
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void removeUserFromChatRoom(Server.ConnectionHandler user) {
        try {
            currentParticipants.remove(user);
            broadcastMessage("-- User " + user.getClientUserName() + " left the chat room --");
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void broadcastMessage(String message) {
        for (Server.ConnectionHandler user : currentParticipants) {
            if (user != null) {
                user.sendMessage(message);
            }
        }
        previousMessages.add(message);
    }

    public void sendPreviousMessages(Server.ConnectionHandler user) {
        try {
            if (user != null) {
                for (String message : previousMessages) {
                    user.sendMessage(message);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
