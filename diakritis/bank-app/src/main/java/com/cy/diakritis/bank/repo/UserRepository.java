package com.cy.diakritis.bank.repo;

import com.cy.diakritis.common.persistence.UserItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists and reads application users ({@code USER#<username>}) in the {@code Users} table. The
 * username is the unique login handle, so it is the partition key (one item per username).
 */
@Repository
public class UserRepository {

    /** pk = {@code USER#<username>}; usernames are the unique login handle. */
    public static final String USER_PK_PREFIX = "USER#";

    private final DynamoDbTable<UserItem> userTable;

    public UserRepository(DynamoDbTable<UserItem> userTable) {
        this.userTable = userTable;
    }

    public static String partitionKeyFor(String username) {
        return USER_PK_PREFIX + username;
    }

    public Optional<UserItem> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder().partitionValue(partitionKeyFor(username)).build();
        return Optional.ofNullable(userTable.getItem(key));
    }

    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    /** Full scan of the user directory — admin-only listing over a small operational table. */
    public List<UserItem> findAll() {
        List<UserItem> users = new ArrayList<>();
        userTable.scan().items().forEach(users::add);
        return users;
    }

    public void save(UserItem item) {
        userTable.putItem(item);
    }

    public void deleteByUsername(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        Key key = Key.builder().partitionValue(partitionKeyFor(username)).build();
        userTable.deleteItem(key);
    }
}
