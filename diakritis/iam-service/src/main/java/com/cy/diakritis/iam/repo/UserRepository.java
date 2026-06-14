package com.cy.diakritis.iam.repo;

import com.cy.diakritis.common.persistence.UserItem;
import com.cy.diakritis.iam.service.ConflictException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactDeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persists and reads application users ({@code USER#<username>}) in the {@code Users} table. The
 * username is the unique login handle, so it is the partition key (one item per username).
 *
 * <p>Usernames are <b>normalized</b> (trimmed + lower-cased via {@link Locale#ROOT}) at every key
 * derivation and lookup so {@code "alice"}, {@code " Alice "} and {@code "ALICE"} all resolve to the
 * single canonical record — this prevents near-duplicate accounts and inconsistent keys.
 *
 * <p>Uniqueness and atomic re-key are enforced with conditional / transactional writes (mirroring
 * {@code DecisionRepository.putIfAbsent}) rather than a non-atomic check-then-put, so concurrent
 * registrations cannot overwrite an existing user and a rename cannot leave two persisted rows.
 */
@Repository
public class UserRepository {

    /** pk = {@code USER#<username>}; usernames are the unique login handle. */
    public static final String USER_PK_PREFIX = "USER#";

    private static final String NOT_EXISTS_PK = "attribute_not_exists(pk)";

    private final DynamoDbTable<UserItem> userTable;
    private final DynamoDbEnhancedClient enhancedClient;

    public UserRepository(DynamoDbTable<UserItem> userTable, DynamoDbEnhancedClient enhancedClient) {
        this.userTable = userTable;
        this.enhancedClient = enhancedClient;
    }

    /**
     * Canonical form of a username: trimmed and lower-cased ({@link Locale#ROOT}). Returns {@code null}
     * for a null input and an empty string for a blank input, so callers can validate after normalizing.
     */
    public static String normalize(String username) {
        if (username == null) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public static String partitionKeyFor(String username) {
        return USER_PK_PREFIX + normalize(username);
    }

    public Optional<UserItem> findByUsername(String username) {
        String canonical = normalize(username);
        if (canonical == null || canonical.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder().partitionValue(partitionKeyFor(canonical)).build();
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

    /**
     * Create a user only if no row already exists for its partition key, mirroring
     * {@code DecisionRepository.putIfAbsent}. The conditional put ({@code attribute_not_exists(pk)})
     * is the authoritative uniqueness guard: it closes the check-then-put race where two concurrent
     * registrations of the same username both pass {@code existsByUsername} and the second silently
     * overwrites the first.
     *
     * @throws ConflictException if a row already exists for the item's key (the conditional check failed)
     */
    public void saveIfAbsent(UserItem item) {
        try {
            userTable.putItem(PutItemEnhancedRequest.builder(UserItem.class)
                    .item(item)
                    .conditionExpression(Expression.builder().expression(NOT_EXISTS_PK).build())
                    .build());
        } catch (ConditionalCheckFailedException alreadyExists) {
            throw new ConflictException("Username already taken: " + item.getUsername());
        }
    }

    /**
     * Atomically re-key a user from {@code oldUsername} to {@code renamed} in a single all-or-nothing
     * {@code TransactWriteItems}: a conditional Put of the new-key item guarded by
     * {@code attribute_not_exists(pk)} (so a colliding target aborts the whole transaction) plus a
     * Delete of the old-key item. This closes the non-atomic write-new-then-delete-old window that
     * could otherwise leave two persisted rows for one identity (or an orphaned login).
     *
     * @throws ConflictException if the target username is already taken (the transaction was cancelled)
     */
    public void renameAtomically(UserItem renamed, String oldUsername) {
        Key oldKey = Key.builder().partitionValue(partitionKeyFor(oldUsername)).build();
        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(userTable, TransactPutItemEnhancedRequest.builder(UserItem.class)
                            .item(renamed)
                            .conditionExpression(Expression.builder().expression(NOT_EXISTS_PK).build())
                            .build())
                    .addDeleteItem(userTable, TransactDeleteItemEnhancedRequest.builder()
                            .key(oldKey)
                            .build())
                    .build());
        } catch (TransactionCanceledException cancelled) {
            // The only conditional in the transaction is the target-collision guard, so a cancellation
            // means the new username was taken concurrently between the pre-check and the commit.
            throw new ConflictException("Username already taken: " + renamed.getUsername());
        }
    }

    public void deleteByUsername(String username) {
        String canonical = normalize(username);
        if (canonical == null || canonical.isBlank()) {
            return;
        }
        Key key = Key.builder().partitionValue(partitionKeyFor(canonical)).build();
        userTable.deleteItem(key);
    }
}
