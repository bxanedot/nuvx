

#[cfg(test)]
mod tests {
    use crate::{prelude::*, DateTime};

    #[test]
    fn test_value() {
        let dt1 = DateTime::now_utc().unwrap();
        let v = dt1.to_value();
        let dt2 = v.get::<&DateTime>().unwrap();

        assert_eq!(dt1.as_ptr(), dt2.as_ptr());
    }
}
