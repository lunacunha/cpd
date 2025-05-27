# Chat Server - Concurrency 

## Connect/Authentication 

### Thread Creation
- *Each client connection spawns a virtual thread for authentication and subsequent message handling.*
```java
Thread.startVirtualThread(srv.new ConnectionHandler(sock));
```
File: Server.java (line 51)


### Shared Data Structures with Synchronization

- *User authentication data is stored in a shared HashMap protected by a ReadWriteLock.*

```java
private final Map<String, User> users = new HashMap<>();
private final ReadWriteLock lock = new ReentrantReadWriteLock();
```
File: UserManager.java (lines 27-28)

### Global Read-Write Lock for User Data

- *Write lock ensures exclusive access during token generation and user authentication.*
```java
public TokenManager authenticate(String user, String pass) {
        String hash = sha256(pass);
        lock.writeLock().lock();
        try {
            User currentUser = users.get(user);
            if (currentUser != null && currentUser.passwordHash.equals(hash)) {
                if (currentUser.token == null || currentUser.token.isExpired()) {
                    currentUser.token = new TokenManager(TOKEN_LIFETIME);
                }
                return currentUser.token;
            }
        } finally {
            lock.writeLock().unlock();
        }
        return null;
    }
```
File: UserManager.java (lines 174-189)


### Session Token Management
- *Read lock allows concurrent token validation across multiple threads.*

```java
public String validateToken(String tokenStr) {
        lock.readLock().lock();
        try {
            for (User currentUser : users.values()) {
                if (currentUser.token != null
                        && currentUser.token.getTokenString().equals(tokenStr)
                        && !currentUser.token.isExpired()) {
                    return currentUser.username;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }
```
File: UserManager.java (lines 209-223)


### Race Condition Prevention 
- *Write lock prevents race conditions during user registration by ensuring atomic check-and-insert operations.*
```java
public boolean registerUser(String username, String password) {
        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            return false;
        }
        String hash = sha256(password);
        lock.writeLock().lock();
        try {
            if (users.containsKey(username)) return false;
            User currentUser = new User(username, hash);
            users.put(username, currentUser);
            saveState();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }
```
File: UserManager.java (lines 248-264)

## Join Operations 

### Room Creation Threading
- *Room creation uses atomic compute operation with write lock to prevent duplicate room creation.*
```java
public ChatRoom getOrCreateAIRoom(String name, String prompt) {
    roomsLock.writeLock().lock();
    try {
        return rooms.compute(name, (rn, existing) -> {
            if (existing == null || !existing.isAI()) {
                return new ChatRoom(rn, prompt);
            } else {
                return existing;
            }
        });
    } finally {
        roomsLock.writeLock().unlock();
    }
}
```
File: Server.java (lines 98-111)


### Shared Room Data Structures
- *Rooms and active clients are stored in shared HashMaps with dedicated locks.*
```java
private final Map<String, ChatRoom> rooms = new HashMap<>();
private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();
private final UserManager userManager = new UserManager();
private final Map<String, PrintWriter> activeClients = new HashMap<>();
```
File: Server.java (lines 21-24)

### Client-Side Room State Synchronization
- *User room assignment is synchronized with write lock and persisted to prevent state inconsistencies.*
```java
public void setRoom(String user, ChatRoom room) {
    lock.writeLock().lock();
    try {
        User currentUser = users.get(user);
        if (currentUser != null) {
            currentUser.room = room;
            saveState();
        }
    } finally {
        lock.writeLock().unlock();
    }
}
```
File: UserManager.java (lines 225-236)

### Race Condition Prevention - Room Creation
- *Atomic computeIfAbsent operation with write lock ensures only one room instance per name.*
```java
public ChatRoom getOrCreateRoom(String name) {
    roomsLock.writeLock().lock();
    try {
        return rooms.computeIfAbsent(name, ChatRoom::new);
    } finally {
        roomsLock.writeLock().unlock();
    }
}
```
File: Server.java (lines 89-96)

## Message Reception 

### Client Message Reception Threading
- *Separate virtual thread handles incoming message reception from server.*
```java
Thread reader = Thread.startVirtualThread(() -> {
    try {
        String line;
        while ((line = in.readLine()) != null) {
            System.out.println(line);
        }
    } catch (IOException e) {
        if (!done) System.err.println("Disconnected from server.");
    } finally {
        done = true;
    }
});
```
File: Client.java (lines 188-199)

### Message Acknowledgment with Shared Data
- *Message history is synchronized with write lock to ensure thread-safe message storage.*
```java
public void addMessage(String message) {
    lock.writeLock().lock();
    try {
        history.add(message);
    } finally {
        lock.writeLock().unlock();
    }
}
```
File: ChatRoom.java (lines 80-87)

### Output Stream Thread Safety
- *Broadcast operations capture active clients under read lock, then safely write to output streams.*
```java
private void broadcast(ChatRoom room, String msg) {
    List<PrintWriter> targets = new ArrayList<>();
    clientsLock.readLock().lock();
    try {
        for (var e : activeClients.entrySet()) {
            if (room.hasUser(e.getKey())) {
                targets.add(e.getValue());
            }
        }
    } finally {
        clientsLock.readLock().unlock();
    }
    for (PrintWriter w : targets) {
        w.println(msg);
    }
}
```
File: Server.java (lines 286-301)

### Message Timeout Monitoring
- *Client polling loop with sleep acts as timeout mechanism for console input availability.*
```java
while (!done) {
    if (!console.ready()) {
        Thread.sleep(100);
        continue;
    }
    String msg = console.readLine();
    // ... message processing ...
}
```
File: Client.java (lines 202-207)

### Acknowledgment Handling
- *Message history retrieval uses read lock allowing concurrent access for message acknowledgment.*
```java
public List<String> getHistory() {
    lock.readLock().lock();
    try {
        return new ArrayList<>(history);
    } finally {
        lock.readLock().unlock();
    }
}
```
File: ChatRoom.java (lines 89-96)

### Race Condition Prevention - Message Acknowledgment
- *User room membership changes are atomic operations preventing inconsistent broadcast targeting.*
```java
public void addUser(String username) {
    lock.writeLock().lock();
    try {
        users.add(username);
    } finally {
        lock.writeLock().unlock();
    }
}

public void removeUser(String username) {
    lock.writeLock().lock();
    try {
        users.remove(username);
    } finally {
        lock.writeLock().unlock();
    }
}
```
File: ChatRoom.java (lines 35-51)

## AI Rooms 

### AI Response - Threading
- *AI responses are generated synchronously within the client connection thread.*
```java
if (room.isAI()) {
    String aiResp = generateAIReply(room);
    String botMsg = "Bot: " + aiResp;
    room.addMessage(botMsg);
    broadcast(room, botMsg);
}
```
File: Server.java (lines 262-267)

### AI Room - Shared Data
- *AI room state including prompt and message history is shared across threads with synchronization.*
```java
private final String chatRoomName;
private final String prompt;      // null for non-AI rooms
private final boolean isAI;
private final Set<String> users = new HashSet<>();
private final List<String> history = new ArrayList<>();
```
File: ChatRoom.java (lines 6-10)

### AI Output Stream Management
- *AI response generation accesses room history through synchronized getHistory() method.*
```java
private String generateAIReply(ChatRoom room) {
    // ... model setup ...
    StringBuilder prompt = new StringBuilder();
    prompt.append(room.getPrompt()).append("\n\n");
    for (String msg : room.getHistory()) {
        prompt.append(msg).append("\n");
    }
    // ... ollama process execution ...
}
```
File: Server.java (lines 303-310)

### Client AI Room Identification
- *AI room identification is thread-safe through immutable boolean field.*
```java
public boolean isAI() {
    return isAI;
}
```
File: ChatRoom.java (lines 27-29)



## Synchronization Summary

**Locks Implemented:**
- `ReadWriteLock roomsLock` (Server.java - line 22) - Room management synchronization
- `ReadWriteLock lock` (UserManager.java - line 28) - User data synchronization  
- `ReadWriteLock clientsLock` (Server.java - line 25) - Active client tracking
- `ReadWriteLock lock` (ChatRoom.java - line 11) - Chat room state synchronization

**Shared Data Structures:**
- `Map<String, ChatRoom> rooms` - Global room registry
- `Map<String, User> users` - User authentication and session data
- `Map<String, PrintWriter> activeClients` - Live connection tracking
- `Set<String> users` & `List<String> history` - Per-room user and message data

**Thread Safety Guarantees:**
- Virtual threads for concurrent client handling without blocking
- Read-write locks allow concurrent reads while ensuring exclusive writes
- Atomic operations (computeIfAbsent, compute) prevent race conditions
- Defensive copying in getters prevents external modification of shared state