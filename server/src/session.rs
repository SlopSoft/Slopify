use std::collections::HashMap;

use argon2::{Argon2, PasswordHash, PasswordVerifier};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, StatusCode, header},
    response::IntoResponse,
    routing::post,
};
use sqlx::{PgPool, types::JsonValue};
use tokio::sync::Semaphore;

pub(crate) static PASSWORD_SLOTS: Semaphore = Semaphore::const_new(2);
// Matching the registration cost keeps unknown usernames on the same verification path.
const DUMMY_HASH: &str = "$argon2id$v=19$m=19456,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

pub fn routes() -> Router<PgPool> {
    Router::new()
        .route("/session", post(login).get(current).delete(logout))
        .layer(DefaultBodyLimit::max(4096))
}

async fn login(
    State(pool): State<PgPool>,
    Json(mut fields): Json<HashMap<String, String>>,
) -> Result<impl IntoResponse, StatusCode> {
    let invalid = StatusCode::UNPROCESSABLE_ENTITY;
    let internal = StatusCode::INTERNAL_SERVER_ERROR;
    let username = fields.remove("username").ok_or(invalid)?;
    let password = fields.remove("password").ok_or(invalid)?;
    if !fields.is_empty() || username.chars().count() > 64 || password.chars().count() > 128 {
        return Err(invalid);
    }
    let slot = PASSWORD_SLOTS
        .try_acquire()
        .map_err(|_| StatusCode::TOO_MANY_REQUESTS)?;
    let account: Option<(i64, String, bool)> =
        sqlx::query_as("SELECT id, password_hash, is_admin FROM accounts WHERE username = $1")
            .bind(username)
            .fetch_optional(&pool)
            .await
            .map_err(|_| internal)?;
    let (id, hash, is_admin) = account.map_or_else(
        || (None, DUMMY_HASH.to_owned(), false),
        |(id, hash, is_admin)| (Some(id), hash, is_admin),
    );
    let valid = tokio::task::spawn_blocking(move || {
        let _permit = slot;
        let parsed = PasswordHash::new(&hash).map_err(|_| internal)?;
        Ok::<_, StatusCode>(
            Argon2::default()
                .verify_password(password.as_bytes(), &parsed)
                .is_ok(),
        )
    })
    .await
    .map_err(|_| internal)??;
    let id = id.filter(|_| valid).ok_or(StatusCode::UNAUTHORIZED)?;
    let mut tx = pool.begin().await.map_err(|_| internal)?;
    sqlx::query("DELETE FROM sessions WHERE expires_at <= now()")
        .execute(&mut *tx)
        .await
        .map_err(|_| internal)?;
    let token: String = sqlx::query_scalar(
        "SELECT encode(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid()), 'hex')",
    )
    .fetch_one(&mut *tx)
    .await
    .map_err(|_| internal)?;
    sqlx::query(
        "INSERT INTO sessions (token_hash, account_id) VALUES (sha256(convert_to($1, 'UTF8')), $2)",
    )
    .bind(&token)
    .bind(id)
    .execute(&mut *tx)
    .await
    .map_err(|_| internal)?;
    tx.commit().await.map_err(|_| internal)?;
    Ok((
        StatusCode::CREATED,
        [(header::CACHE_CONTROL, "no-store")],
        Json(HashMap::from([
            ("token", JsonValue::String(token)),
            ("is_admin", JsonValue::Bool(is_admin)),
        ])),
    ))
}

pub(crate) fn bearer(headers: &HeaderMap) -> Result<&str, StatusCode> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split_once(' '))
        .filter(|(scheme, t)| {
            scheme.eq_ignore_ascii_case("Bearer")
                && t.len() == 64
                && t.bytes().all(|b| b.is_ascii_hexdigit())
        })
        .map(|(_, t)| t)
        .ok_or(StatusCode::UNAUTHORIZED)
}

async fn current(
    State(pool): State<PgPool>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, StatusCode> {
    let username = authenticated_username(&pool, &headers).await?;
    Ok((
        [(header::CACHE_CONTROL, "no-store")],
        Json(HashMap::from([("username", username)])),
    ))
}

pub(crate) async fn authenticated_username(
    pool: &PgPool,
    headers: &HeaderMap,
) -> Result<String, StatusCode> {
    sqlx::query_scalar(
        "SELECT a.username FROM accounts a JOIN sessions s ON s.account_id = a.id
         WHERE s.token_hash = sha256(convert_to($1, 'UTF8')) AND s.expires_at > now()",
    )
    .bind(bearer(headers)?)
    .fetch_optional(pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    .ok_or(StatusCode::UNAUTHORIZED)
}

async fn logout(State(pool): State<PgPool>, headers: HeaderMap) -> Result<StatusCode, StatusCode> {
    sqlx::query("DELETE FROM sessions WHERE token_hash=sha256(convert_to($1,'UTF8'))")
        .bind(bearer(&headers)?)
        .execute(&pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(StatusCode::NO_CONTENT)
}
