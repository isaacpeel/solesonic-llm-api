package com.solesonic.model.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
import com.solesonic.model.rag.PrincipalType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One grant: this principal may do this to this document.
 * <p>
 * The row that replaces {@code ingested_document.scope}, {@code user_id} and {@code chat_id}. Those
 * three encoded one fact as a nullable-column discriminated union whose invariant — exactly one
 * owning column set, agreeing with the scope — was enforced by nothing but every call site
 * remembering. A grant is a row: absent means not entitled, and a fourth ownership shape is a fourth
 * row rather than a fourth column and a fifth unenforced rule.
 * <p>
 * A loose {@code UUID} reference to the document rather than a JPA association, matching how
 * {@link StatusHistory#getDocumentId()} refers to its parent. The database still enforces the
 * relationship: the FK carries {@code ON DELETE CASCADE}, so deleting a document takes its grants
 * with it without any code remembering to.
 */
@Entity
public class DocumentEntitlement {

    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID ingestedDocumentId;

    @Enumerated(EnumType.STRING)
    private PrincipalType principalType;

    /**
     * Text, not a {@code UUID}: {@code GLOBAL} carries
     * {@link DocumentPrincipal#GLOBAL_SENTINEL} and {@code ROLE} carries a role name.
     */
    private String principalId;

    @Enumerated(EnumType.STRING)
    private GrantKind grantKind;

    private ZonedDateTime grantedAt;

    /**
     * Who performed the grant, where that is known.
     * <p>
     * Not the same question as who the grant is <em>to</em>. It is what preserves "which admin added
     * this" for a global document, whose only {@code MANAGE} grant is the {@code rag-admin} role and
     * which therefore records no person anywhere else.
     */
    private UUID grantedBy;

    public DocumentEntitlement() {
    }

    public DocumentEntitlement(UUID ingestedDocumentId,
                               DocumentPrincipal principal,
                               GrantKind grantKind,
                               UUID grantedBy) {
        this.ingestedDocumentId = ingestedDocumentId;
        this.principalType = principal.type();
        this.principalId = principal.id();
        this.grantKind = grantKind;
        this.grantedBy = grantedBy;
        this.grantedAt = ZonedDateTime.now();
    }

    /**
     * The pair of columns read back as the thing they mean.
     */
    public DocumentPrincipal principal() {
        return new DocumentPrincipal(principalType, principalId);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getIngestedDocumentId() {
        return ingestedDocumentId;
    }

    public void setIngestedDocumentId(UUID ingestedDocumentId) {
        this.ingestedDocumentId = ingestedDocumentId;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(PrincipalType principalType) {
        this.principalType = principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public GrantKind getGrantKind() {
        return grantKind;
    }

    public void setGrantKind(GrantKind grantKind) {
        this.grantKind = grantKind;
    }

    public ZonedDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(ZonedDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(UUID grantedBy) {
        this.grantedBy = grantedBy;
    }
}
