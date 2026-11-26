use std::collections::HashMap;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, StatusCode, header},
    response::IntoResponse,
    routing::post,
};
use sqlx::PgPool;

use crate::{session::bearer, setup};

const INTERNAL: StatusCode = StatusCode::INTERNAL_SERVER_ERROR;

pub(crate) fn routes() -> Router<PgPool> {
    Router::new()
        .route("/invitations", post(create))
        .route("/accounts", post(register))
        .layer(DefaultBodyLimit::max(4096))
}

async fn create(
    State(pool): State<PgPool>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, StatusCode> {
    let token = bearer(&headers)?;
    let mut tx = pool.begin().await.map_err(|_| INTERNAL)?;
    let is_admin: bool = sqlx::query_scalar(
        "SELECT a.is_admin FROM accounts a JOIN sessions s ON s.account_id = a.id
         WHERE s.token_hash = sha256(convert_to($1, 'UTF8')) AND s.expires_at > now()",
    )
    .bind(token)
    .fetch_optional(&mut *tx)
    .await
    .map_err(|_| INTERNAL)?
    .ok_or(StatusCode::UNAUTHORIZED)?;
    if !is_admin {
        return Err(StatusCode::FORBIDDEN);
    }

    sqlx::query("DELETE FROM invitations WHERE expires_at <= now()")
        .execute(&mut *tx)
        .await
        .map_err(|_| INTERNAL)?;
    let code: String = sqlx::query_scalar("SELECT gen_random_uuid()::text")
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| INTERNAL)?;
    sqlx::query("INSERT INTO invitations (code_hash) VALUES (sha256(convert_to($1, 'UTF8')))")
        .bind(&code)
        .execute(&mut *tx)
        .await
        .map_err(|_| INTERNAL)?;
    tx.commit().await.map_err(|_| INTERNAL)?;
    Ok((
        StatusCode::CREATED,
        [(header::CACHE_CONTROL, "no-store")],
        Json(HashMap::from([("code", code)])),
    ))
}

async fn register(
    State(pool): State<PgPool>,
    Json(mut fields): Json<HashMap<String, String>>,
) -> Result<StatusCode, StatusCode> {
    let code = fields
        .remove("code")
        .ok_or(StatusCode::UNPROCESSABLE_ENTITY)?;
    let (username, password) = setup::credentials(fields)?;
    if code.len() != 36 {
        return Err(StatusCode::FORBIDDEN);
    }
    let valid: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM invitations
         WHERE code_hash = sha256(convert_to($1, 'UTF8')) AND expires_at > now())",
    )
    .bind(&code)
    .fetch_one(&pool)
    .await
    .map_err(|_| INTERNAL)?;
    if !valid {
        return Err(StatusCode::FORBIDDEN);
    }
    let hash = setup::hash_password(password).await?;
    let mut tx = pool.begin().await.map_err(|_| INTERNAL)?;
    let consumed = sqlx::query(
        "DELETE FROM invitations
         WHERE code_hash = sha256(convert_to($1, 'UTF8')) AND expires_at > clock_timestamp()",
    )
    .bind(code)
    .execute(&mut *tx)
    .await
    .map_err(|_| INTERNAL)?
    .rows_affected();
    if consumed == 0 {
        return Err(StatusCode::FORBIDDEN);
    }
    sqlx::query("INSERT INTO accounts (username, password_hash) VALUES ($1, $2)")
        .bind(username)
        .bind(hash)
        .execute(&mut *tx)
        .await
        .map_err(|error| match error.as_database_error() {
            Some(error) if error.is_unique_violation() => StatusCode::CONFLICT,
            _ => INTERNAL,
        })?;
    tx.commit().await.map_err(|_| INTERNAL)?;
    Ok(StatusCode::CREATED)
}
