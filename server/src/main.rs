use std::{env, error::Error, fs, io, time::Duration};

use axum::{Router, extract::State, http::StatusCode, routing::get};
use sqlx::{
    PgPool,
    postgres::{PgConnectOptions, PgPoolOptions},
};
use tokio::{net::TcpListener, time::timeout};

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let password = fs::read_to_string(env::var_os("POSTGRES_PASSWORD_FILE").ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::NotFound,
            "POSTGRES_PASSWORD_FILE is required",
        )
    })?)?;
    let password = password.trim_end_matches(['\r', '\n']);
    if password.is_empty() {
        return Err(
            io::Error::new(io::ErrorKind::InvalidData, "PostgreSQL password is empty").into(),
        );
    }

    let options = PgConnectOptions::new()
        .host(&env::var("POSTGRES_HOST").unwrap_or_else(|_| "127.0.0.1".into()))
        .username(&env::var("POSTGRES_USER").unwrap_or_else(|_| "postgres".into()))
        .database(&env::var("POSTGRES_DB").unwrap_or_else(|_| "slopify".into()))
        .password(password);
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect_with(options)
        .await?;
    sqlx::migrate!().run(&pool).await?;

    let address = env::var("SLOPIFY_LISTEN_ADDR").unwrap_or_else(|_| "127.0.0.1:8080".into());
    let listener = TcpListener::bind(address).await?;
    println!("Listening on {}", listener.local_addr()?);
    let app = Router::new().route("/health", get(health)).with_state(pool);
    axum::serve(listener, app).await?;
    Ok(())
}

async fn health(State(pool): State<PgPool>) -> StatusCode {
    match timeout(
        Duration::from_secs(2),
        sqlx::query("SELECT 1").execute(&pool),
    )
    .await
    {
        Ok(Ok(_)) => StatusCode::OK,
        _ => StatusCode::SERVICE_UNAVAILABLE,
    }
}
