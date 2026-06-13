package com.cy.diakritis.bank.repo;

import com.cy.diakritis.common.persistence.RefreshTokenItem;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Persists and reads opaque refresh tokens ({@code RT#<tokenId>}) in the {@code RefreshTokens} table.
 * The raw token id is the partition key — server-side validation is "row exists, not revoked, not
 * expired"; rotation revokes the presented row and writes a fresh one.
 */
@Repository
public class RefreshTokenRepository {

    public static final String RT_PK_PREFIX = "RT#";

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
}
