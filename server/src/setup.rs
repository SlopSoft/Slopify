use std::collections::HashMap;

use argon2::{
    Argon2, PasswordHasher,
    password_hash::{SaltString, rand_core::OsRng},
};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::{StatusCode, header},
    response::IntoResponse,
    routing::get,
};
use sqlx::PgPool;

pub fn routes() -> Router<PgPool> {
    Router::new()
        .route("/setup", get(status).post(register))
        .layer(DefaultBodyLimit::max(4096))
}

pub(crate) async fn log_pending_code(pool: &PgPool) -> Result<(), sqlx::Error> {
    let code: Option<String> = sqlx::query_scalar("SELECT code::text FROM admin_setup")
        .fetch_optional(pool)
        .await?;
    if let Some(code) = code {
        println!("First administrator setup code: {code}");
    }
    Ok(())
}

async fn setup_required(pool: &PgPool) -> Result<bool, StatusCode> {
    sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM admin_setup)")
        .fetch_one(pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

async fn status(State(pool): State<PgPool>) -> Result<impl IntoResponse, StatusCode> {
    let required = setup_required(&pool).await?;
    Ok((
        [(header::CACHE_CONTROL, "no-store")],
        Json(HashMap::from([("registration_required", required)])),
    ))
}

async fn register(
    State(pool): State<PgPool>,
    Json(mut fields): Json<HashMap<String, String>>,
) -> Result<StatusCode, StatusCode> {
    let invalid = StatusCode::UNPROCESSABLE_ENTITY;
    let internal = StatusCode::INTERNAL_SERVER_ERROR;
    let code = fields.remove("code").ok_or(invalid)?;
    let (username, password) = credentials(fields)?;

    let mut tx = pool.begin().await.map_err(|_| internal)?;
    // Concurrent registrations contend for this single row. Rollback restores the code.
    let consumed = sqlx::query("DELETE FROM admin_setup WHERE code::text = $1")
        .bind(code)
        .execute(&mut *tx)
        .await
        .map_err(|_| internal)?
        .rows_affected();
    if consumed == 0 {
        tx.rollback().await.map_err(|_| internal)?;
        return Err(if setup_required(&pool).await? {
            StatusCode::FORBIDDEN
        } else {
            StatusCode::CONFLICT
        });
    }

    let hash = hash_password(password).await?;
    sqlx::query("INSERT INTO accounts (username, password_hash, is_admin) VALUES ($1, $2, true)")
        .bind(username)
        .bind(hash)
        .execute(&mut *tx)
        .await
        .map_err(|_| internal)?;
    tx.commit().await.map_err(|_| internal)?;
    Ok(StatusCode::CREATED)
}

pub(crate) fn credentials(
    mut fields: HashMap<String, String>,
) -> Result<(String, String), StatusCode> {
    let invalid = StatusCode::UNPROCESSABLE_ENTITY;
    let username = fields.remove("username").ok_or(invalid)?;
    let password = fields.remove("password").ok_or(invalid)?;
    if !fields.is_empty()
        || username.trim() != username
        || username.chars().any(char::is_control)
        || !(1..=64).contains(&username.chars().count())
        || !(15..=128).contains(&password.chars().count())
    {
        return Err(invalid);
    }
    Ok((username, password))
}

pub(crate) async fn hash_password(password: String) -> Result<String, StatusCode> {
    let internal = StatusCode::INTERNAL_SERVER_ERROR;
    let slot = crate::session::PASSWORD_SLOTS
        .try_acquire()
        .map_err(|_| StatusCode::TOO_MANY_REQUESTS)?;
    tokio::task::spawn_blocking(move || {
        let _permit = slot;
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|hash| hash.to_string())
    })
    .await
    .map_err(|_| internal)?
    .map_err(|_| internal)
}
