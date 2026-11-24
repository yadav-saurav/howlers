package app.howlers.auth.passkey;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The library's view of {@code user_passkeys} and {@code account_security.webauthn_user_handle}. */
class PasskeyCredentialRepository implements CredentialRepository {
    private final JdbcClient jdbc;

    PasskeyCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        List<byte[]> ids = jdbc.sql("""
                        SELECT p.credential_id FROM user_passkeys p JOIN users u ON u.id = p.user_id
                        WHERE lower(u.username) = lower(?)""")
                .param(username).query((rs, n) -> rs.getBytes(1)).list();
        Set<PublicKeyCredentialDescriptor> out = new HashSet<>();
        for (byte[] id : ids) out.add(PublicKeyCredentialDescriptor.builder().id(new ByteArray(id)).build());
        return out;
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return jdbc.sql("""
                        SELECT s.webauthn_user_handle FROM account_security s JOIN users u ON u.id = s.user_id
                        WHERE lower(u.username) = lower(?) AND s.webauthn_user_handle IS NOT NULL""")
                .param(username).query((rs, n) -> new ByteArray(rs.getBytes(1))).optional();
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return jdbc.sql("""
                        SELECT u.username FROM account_security s JOIN users u ON u.id = s.user_id
                        WHERE s.webauthn_user_handle = ?""")
                .param(userHandle.getBytes()).query(String.class).optional();
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return jdbc.sql("""
                        SELECT p.credential_id, s.webauthn_user_handle, p.public_key_cose, p.sign_count, p.backup_eligible, p.backed_up
                        FROM user_passkeys p JOIN account_security s ON s.user_id = p.user_id
                        WHERE p.credential_id = ? AND s.webauthn_user_handle = ?""")
                .params(credentialId.getBytes(), userHandle.getBytes())
                .query((rs, n) -> credential(rs)).optional();
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return new HashSet<>(jdbc.sql("""
                        SELECT p.credential_id, s.webauthn_user_handle, p.public_key_cose, p.sign_count, p.backup_eligible, p.backed_up
                        FROM user_passkeys p JOIN account_security s ON s.user_id = p.user_id
                        WHERE p.credential_id = ?""")
                .param(credentialId.getBytes())
                .query((rs, n) -> credential(rs)).list());
    }

    private static RegisteredCredential credential(java.sql.ResultSet rs) throws java.sql.SQLException {
        return RegisteredCredential.builder()
                .credentialId(new ByteArray(rs.getBytes(1)))
                .userHandle(new ByteArray(rs.getBytes(2)))
                .publicKeyCose(new ByteArray(rs.getBytes(3)))
                .signatureCount(rs.getLong(4))
                .backupEligible(rs.getBoolean(5))
                .backupState(rs.getBoolean(6))
                .build();
    }
}
