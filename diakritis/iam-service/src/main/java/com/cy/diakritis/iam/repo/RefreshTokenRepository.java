package com.cy.diakritis.iam.repo;

import com.cy.diakritis.common.persistence.RefreshTokenItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;
import java.util.Optional;

/**
 * Persists and reads opaque refresh tokens ({@code RT#<tokenId>}) in the {@code RefreshTokens} table.
 * The raw token id is the partition key — server-side validation is "row exists, not revoked, not
 * expired"; rotation revokes the presented row and writes a fresh one.
 */
@Repository
public class RefreshTokenRepository {

    public static final String RT_PK_PREFIX = "RT#";

    /** Compare-and-set guard so only one concurrent caller can consume (revoke) an active token. */
    private static final String STILL_ACTIVE = "revoked = :false";
    private static final String REVOKED_PLACEHOLDER = ":false";

    private final DynamoDbTable<RefreshTokenItem> refreshTokenTable;

    public RefreshTokenRepository(DynamoDbTable<RefreshTokenItem> refreshTokenTable) {
        this.refreshTokenTable = refreshTokenTable;
    }

    public static String partitionKeyFor(String tokenId) {
        return RT_PK_PREFIX + tokenId;
    }

    public Optional<RefreshTokenItem> findByTokenId(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        Key key = Key.builder().partitionValue(partitionKeyFor(tokenId)).build();
        return Optional.ofNullable(refreshTokenTable.getItem(key));
    }

    public void save(RefreshTokenItem item) {
        refreshTokenTable.putItem(item);
    }

    /**
     * Atomically revoke a token only if it is still active ({@code revoked = false}). The conditional
     * update serializes concurrent rotations so exactly one caller can consume a given refresh token:
     * the winner's update commits, every loser fails the condition. This closes the TOCTOU race where
     * two concurrent {@code /auth/refresh} calls both read {@code revoked = false} and both rotate,
     * forking a single presented token into two live token chains.
     *
     * @return {@code true} if this caller won (the token was active and is now revoked), {@code false}
     * if the token had already been revoked by a concurrent caller (the condition failed).
     */
    public boolean revokeIfActive(RefreshTokenItem item) {
        item.setRevoked(true);
        try {
            refreshTokenTable.updateItem(UpdateItemEnhancedRequest.builder(RefreshTokenItem.class)
                    .item(item)
                    .ignoreNulls(true)
                    .conditionExpression(Expression.builder()
                            .expression(STILL_ACTIVE)
                            .expressionValues(Map.of(
                                    REVOKED_PLACEHOLDER, AttributeValue.builder().bool(false).build()))
                            .build())
                    .build());
            return true;
        } catch (ConditionalCheckFailedException alreadyRevoked) {
            return false;
        }
    }
}
