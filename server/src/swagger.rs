use axum::{Router, http::header, routing::get};
use utoipa_swagger_ui::{Config, SwaggerUi};

pub fn routes() -> Router {
    const OPENAPI_PATH: &str = "/openapi.json";
    let config = Config::from(OPENAPI_PATH).validator_url("none");

    Router::new()
        .route(
            OPENAPI_PATH,
            get(|| async {
                (
                    [(header::CONTENT_TYPE, "application/json")],
                    include_str!("openapi.json"),
                )
            }),
        )
        .merge(SwaggerUi::new("/swagger-ui").config(config))
}
