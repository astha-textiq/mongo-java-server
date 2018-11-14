package de.bwaldvogel.mongo.backend.aggregation;

import static de.bwaldvogel.mongo.backend.Missing.isNullOrMissing;
import static de.bwaldvogel.mongo.backend.Utils.describeType;
import static de.bwaldvogel.mongo.bson.Json.toJsonValue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import de.bwaldvogel.mongo.backend.Missing;
import de.bwaldvogel.mongo.backend.Utils;
import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.exception.MongoServerError;

public enum Expression implements ExpressionTraits {

    $abs {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, Math::abs);
        }
    },

    $add {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            boolean returnDate = false;
            Number sum = 0;
            for (Object value : expressionValue) {
                Object number = value;
                if (isNullOrMissing(number)) {
                    return null;
                }
                if (!(number instanceof Number) && !(number instanceof Date)) {
                    throw new MongoServerError(16554,
                        name() + " only supports numeric or date types, not " + describeType(number));
                }
                if (number instanceof Date) {
                    number = ((Date) number).getTime();
                    returnDate = true;
                }
                sum = Utils.addNumbers(sum, (Number) number);
            }
            if (returnDate) {
                return new Date(sum.longValue());
            }
            return sum;
        }
    },

    $and {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            for (Object value : expressionValue) {
                if (!Utils.isTrue(value)) {
                    return false;
                }
            }
            return true;
        }
    },

    $anyElementTrue {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Object valueInCollection = requireSingleValue(expressionValue);
            if (!(valueInCollection instanceof Collection)) {
                throw new MongoServerError(17041,
                    name() + "'s argument must be an array, but is " + describeType(valueInCollection));
            }
            Collection<?> collectionInCollection = (Collection<?>) valueInCollection;
            for (Object value : collectionInCollection) {
                if (Utils.isTrue(value)) {
                    return true;
                }
            }
            return false;
        }
    },

    $allElementsTrue {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Object parameter = requireSingleValue(expressionValue);
            if (!(parameter instanceof Collection)) {
                throw new MongoServerError(17040,
                    name() + "'s argument must be an array, but is " + describeType(parameter));
            }
            Collection<?> collectionInCollection = (Collection<?>) parameter;
            for (Object value : collectionInCollection) {
                if (!Utils.isTrue(value)) {
                    return false;
                }
            }
            return true;
        }
    },

    $arrayElemAt {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);

            if (parameters.isAnyNull()) {
                return null;
            }

            Object firstValue = parameters.getFirst();
            Object secondValue = parameters.getSecond();
            if (!(firstValue instanceof List<?>)) {
                throw new MongoServerError(28689,
                    name() + "'s first argument must be an array, but is " + describeType(firstValue));
            }
            if (!(secondValue instanceof Number)) {
                throw new MongoServerError(28690,
                    name() + "'s second argument must be a numeric value, but is " + describeType(secondValue));
            }
            List<?> collection = (List<?>) firstValue;
            int index = ((Number) secondValue).intValue();
            if (index < 0) {
                index = collection.size() + index;
            }
            if (index < 0 || index >= collection.size()) {
                return null;
            } else {
                return collection.get(index);
            }
        }
    },

    $ceil {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, a -> toIntOrLong(Math.ceil(a)));
        }
    },

    $cmp {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue);
        }
    },

    $concat {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            StringBuilder result = new StringBuilder();
            for (Object value : expressionValue) {
                if (isNullOrMissing(value)) {
                    return null;
                }
                if (!(value instanceof String)) {
                    throw new MongoServerError(16702,
                        name() + " only supports strings, not " + describeType(value));
                }
                result.append(value);
            }
            return result.toString();
        }
    },

    $concatArrays {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            List<Object> result = new ArrayList<>();
            for (Object value : expressionValue) {
                if (isNullOrMissing(value)) {
                    return null;
                }
                if (!(value instanceof Collection<?>)) {
                    throw new MongoServerError(28664,
                        name() + " only supports arrays, not " + describeType(value));
                }
                result.addAll((Collection<?>) value);
            }
            return result;
        }
    },

    $cond {
        @Override
        Object apply(List<?> expressionValue, Document document) {

            final Object ifExpression;
            final Object thenExpression;
            final Object elseExpression;

            if (expressionValue.size() == 1 && expressionValue.get(0) instanceof Document) {
                Document condDocument = (Document) expressionValue.get(0);
                List<String> requiredKeys = Arrays.asList("if", "then", "else");
                for (String requiredKey : requiredKeys) {
                    if (!condDocument.containsKey(requiredKey)) {
                        throw new MongoServerError(17080, "Missing '" + requiredKey + "' parameter to " + name());
                    }
                }
                for (String key : condDocument.keySet()) {
                    if (!requiredKeys.contains(key)) {
                        throw new MongoServerError(17083, "Unrecognized parameter to " + name() + ": " + key);
                    }
                }

                ifExpression = condDocument.get("if");
                thenExpression = condDocument.get("then");
                elseExpression = condDocument.get("else");
            } else {
                List<?> collection = requireCollectionInSize(expressionValue, 3);
                ifExpression = collection.get(0);
                thenExpression = collection.get(1);
                elseExpression = collection.get(2);
            }

            if (Utils.isTrue(evaluate(ifExpression, document))) {
                return evaluate(thenExpression, document);
            } else {
                return evaluate(elseExpression, document);
            }
        }
    },

    $dayOfMonth {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateDate(expressionValue, LocalDate::getDayOfMonth, document);
        }
    },

    $dayOfWeek {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateDate(expressionValue, date -> date.getDayOfWeek().getValue(), document);
        }
    },

    $dayOfYear {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateDate(expressionValue, LocalDate::getDayOfYear, document);
        }
    },

    $divide {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoNumericParameters parameters = requireTwoNumericParameters(expressionValue, 16609);

            if (parameters == null) {
                return null;
            }

            double a = parameters.getFirst();
            double b = parameters.getSecond();
            if (Double.compare(b, 0.0) == 0) {
                throw new MongoServerError(16608, "can't " + name() + " by zero");
            }
            return a / b;
        }
    },

    $eq {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue, v -> v == 0);
        }
    },

    $exp {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, Math::exp);
        }
    },

    $filter {
        @Override
        Object apply(Object expressionValue, Document document) {
            Document filterExpression = requireDocument(expressionValue, 28646);
            List<String> requiredKeys = Arrays.asList("input", "cond");
            for (String requiredKey : requiredKeys) {
                if (!filterExpression.containsKey(requiredKey)) {
                    throw new MongoServerError(28648, "Missing '" + requiredKey + "' parameter to " + name());
                }
            }

            for (String key : filterExpression.keySet()) {
                if (!Arrays.asList("input", "cond", "as").contains(key)) {
                    throw new MongoServerError(28647, "Unrecognized parameter to " + name() + ": " + key);
                }
            }

            Object input = evaluate(filterExpression.get("input"), document);
            Object as = evaluate(filterExpression.getOrDefault("as", "this"), document);
            if (!(as instanceof String) || Objects.equals(as, "")) {
                throw new MongoServerError(16866, "empty variable names are not allowed");
            }
            if (Missing.isNullOrMissing(input)) {
                return null;
            }

            if (!(input instanceof Collection)) {
                throw new MongoServerError(28651, "input to " + name() + " must be an array not " + describeType(input));
            }

            Collection<?> inputCollection = (Collection<?>) input;

            String key = "$" + as;
            Document documentForCondition = document.clone();
            if (documentForCondition.containsKey(key)) {
                throw new IllegalArgumentException("Document contains " + key + ". This must not happen");
            }
            List<Object> result = new ArrayList<>();
            for (Object inputValue : inputCollection) {
                Object evaluatedInputValue = evaluate(inputValue, document);
                documentForCondition.put(key, evaluatedInputValue);
                if (Utils.isTrue(evaluate(filterExpression.get("cond"), documentForCondition))) {
                    result.add(evaluatedInputValue);
                }
            }
            return result;
        }

        @Override
        Object apply(List<?> expressionValue, Document document) {
            throw new UnsupportedOperationException("must not be invoked");
        }
    },

    $floor {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, a -> toIntOrLong(Math.floor(a)));
        }
    },

    $gt {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue, v -> v > 0);
        }
    },

    $gte {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue, v -> v >= 0);
        }
    },

    $hour {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateTime(expressionValue, LocalTime::getHour, document);
        }
    },

    $ifNull {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);
            Object expression = parameters.getFirst();
            if (!isNullOrMissing(expression)) {
                return expression;
            } else {
                return parameters.getSecond();
            }
        }
    },

    $in {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);
            Object needle = parameters.getFirst();
            Object haystack = parameters.getSecond();

            if (!(haystack instanceof Collection)) {
                throw new MongoServerError(40081, name() + " requires an array as a second argument, found: " + describeType(haystack));
            }

            return ((Collection<?>) haystack).contains(needle);
        }
    },

    $indexOfArray {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            if (expressionValue.size() < 2 || expressionValue.size() > 4) {
                throw new MongoServerError(28667,
                    "Expression " + name() + " takes at least 2 arguments, and at most 4, but " + expressionValue.size() + " were passed in.");
            }

            Object first = expressionValue.get(0);
            if (isNullOrMissing(first)) {
                return null;
            }
            if (!(first instanceof List<?>)) {
                throw new MongoServerError(40090,
                    name() + " requires an array as a first argument, found: " + describeType(first));
            }
            List<?> elementsToSearchIn = (List<?>) first;

            int start = 0;
            if (expressionValue.size() >= 3) {
                Object startValue = expressionValue.get(2);
                start = requireIntegral(startValue, "starting index");
                start = Math.min(start, elementsToSearchIn.size());
            }

            int end = elementsToSearchIn.size();
            if (expressionValue.size() >= 4) {
                Object endValue = expressionValue.get(3);
                end = requireIntegral(endValue, "ending index");
                end = Math.min(Math.max(start, end), elementsToSearchIn.size());
            }

            elementsToSearchIn = elementsToSearchIn.subList(start, end);
            int index = elementsToSearchIn.indexOf(expressionValue.get(1));
            if (index >= 0) {
                return index + start;
            }
            return index;
        }
    },

    $indexOfBytes {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateIndexOf(expressionValue, this::toList,
                40091, 40092);
        }

        private List<Byte> toList(String input) {
            List<Byte> bytes = new ArrayList<>();
            for (byte value : input.getBytes(StandardCharsets.UTF_8)) {
                bytes.add(value);
            }
            return bytes;
        }

    },

    $indexOfCP {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateIndexOf(expressionValue, this::toList,
                40093, 40094);
        }

        private List<Character> toList(String input) {
            List<Character> characters = new ArrayList<>();
            for (char value : input.toCharArray()) {
                characters.add(value);
            }
            return characters;
        }

    },

    $isArray {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Object value = requireSingleValue(expressionValue);
            return (value instanceof List);
        }
    },

    $literal {
        @Override
        Object apply(Object expressionValue, Document document) {
            return expressionValue;
        }

        @Override
        Object apply(List<?> expressionValue, Document document) {
            throw new UnsupportedOperationException("must not be invoked");
        }
    },

    $ln {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, Math::log);
        }
    },

    $log {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, Math::log);
        }
    },

    $log10 {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, Math::log10);
        }
    },

    $lt {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue, v -> v < 0);
        }
    },

    $lte {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue, v -> v <= 0);
        }
    },


    $map {
        @Override
        Object apply(Object expressionValue, Document document) {
            Document filterExpression = requireDocument(expressionValue, 16878);
            List<String> requiredKeys = Arrays.asList("input", "in");
            for (String requiredKey : requiredKeys) {
                if (!filterExpression.containsKey(requiredKey)) {
                    throw new MongoServerError(16882, "Missing '" + requiredKey + "' parameter to " + name());
                }
            }

            for (String key : filterExpression.keySet()) {
                if (!Arrays.asList("input", "in", "as").contains(key)) {
                    throw new MongoServerError(16879, "Unrecognized parameter to " + name() + ": " + key);
                }
            }

            Object input = evaluate(filterExpression.get("input"), document);
            Object as = evaluate(filterExpression.getOrDefault("as", "this"), document);
            if (!(as instanceof String) || Objects.equals(as, "")) {
                throw new MongoServerError(16866, "empty variable names are not allowed");
            }
            if (Missing.isNullOrMissing(input)) {
                return null;
            }

            if (!(input instanceof Collection)) {
                throw new MongoServerError(16883, "input to " + name() + " must be an array not " + describeType(input));
            }

            Collection<?> inputCollection = (Collection<?>) input;

            String key = "$" + as;
            Document documentForCondition = document.clone();
            if (documentForCondition.containsKey(key)) {
                throw new IllegalArgumentException("Document contains " + key + ". This must not happen");
            }
            List<Object> result = new ArrayList<>();
            for (Object inputValue : inputCollection) {
                Object evaluatedInputValue = evaluate(inputValue, document);
                documentForCondition.put(key, evaluatedInputValue);
                result.add(evaluate(filterExpression.get("in"), documentForCondition));
            }
            return result;
        }

        @Override
        Object apply(List<?> expressionValue, Document document) {
            throw new UnsupportedOperationException("must not be invoked");
        }
    },

    $mergeObjects {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Document result = new Document();
            for (Object value : expressionValue) {
                if (isNullOrMissing(value)) {
                    continue;
                }
                if (!(value instanceof Document)) {
                    throw new MongoServerError(40400,
                        "$mergeObjects requires object inputs, but input " + toJsonValue(value) + " is of type " + describeType(value));
                }
                result.putAll((Document) value);
            }
            return result;
        }
    },

    $minute {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateTime(expressionValue, LocalTime::getMinute, document);
        }
    },

    $mod {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoNumericParameters parameters = requireTwoNumericParameters(expressionValue, 16611);
            if (parameters == null) {
                return null;
            }
            double a = parameters.getFirst();
            double b = parameters.getSecond();
            return a % b;
        }
    },

    $month {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateDate(expressionValue, date -> date.getMonth().getValue(), document);
        }
    },

    $multiply {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoNumericParameters parameters = requireTwoNumericParameters(expressionValue, 16555);

            if (parameters == null) {
                return null;
            }

            double a = parameters.getFirst();
            double b = parameters.getSecond();
            return a * b;
        }
    },


    $ne {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateComparison(expressionValue, v -> v != 0);
        }
    },

    $not {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Object value = requireSingleValue(expressionValue);
            return !Utils.isTrue(value);
        }
    },

    $or {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            for (Object value : expressionValue) {
                if (Utils.isTrue(value)) {
                    return true;
                }
            }
            return false;
        }
    },


    $pow {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);
            if (parameters.isAnyNull()) {
                return null;
            }

            Object base = parameters.getFirst();
            Object exponent = parameters.getSecond();

            if (!(base instanceof Number)) {
                throw new MongoServerError(28762,
                    name() + "'s base must be numeric, not " + describeType(base));
            }

            if (!(exponent instanceof Number)) {
                throw new MongoServerError(28763,
                    name() + "'s exponent must be numeric, not " + describeType(exponent));
            }

            double a = ((Number) base).doubleValue();
            double b = ((Number) exponent).doubleValue();
            return Math.pow(a, b);
        }
    },

    $range {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            if (expressionValue.size() < 2 || expressionValue.size() > 3) {
                throw new MongoServerError(28667, "Expression " + name() + " takes at least 2 arguments, and at most 3, but " + expressionValue.size() + " were passed in.");
            }

            Object first = expressionValue.get(0);
            Object second = expressionValue.get(1);

            int start = toInt(first, 34443, 34444, "starting value");
            int end = toInt(second, 34445, 34446, "ending value");

            final int step;
            if (expressionValue.size() > 2) {
                Object third = expressionValue.get(2);
                step = toInt(third, 34447, 34448, "step value");
                if (step == 0) {
                    throw new MongoServerError(34449, name() + " requires a non-zero step value");
                }
            } else {
                step = 1;
            }

            List<Integer> values = new ArrayList<>();
            if (step > 0) {
                for (int i = start; i < end; i += step) {
                    values.add(i);
                }
            } else {
                for (int i = start; i > end; i += step) {
                    values.add(i);
                }
            }
            return values;
        }

        private int toInt(Object object, int errorCodeIfNotANumber, int errorCodeIfNonInt, String errorMessage) {
            if (!(object instanceof Number)) {
                throw new MongoServerError(errorCodeIfNotANumber, name() + " requires a numeric " + errorMessage + ", found value of type: " + describeType(object));
            }
            Number number = (Number) object;
            int value = number.intValue();
            if (number.doubleValue() != value) {
                throw new MongoServerError(errorCodeIfNonInt,
                    name() + " requires a " + errorMessage + " that can be represented as a 32-bit integer, found value: " + number);
            }
            return value;
        }
    },

    $reverseArray {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Object value = requireSingleValue(expressionValue);
            if (isNullOrMissing(value)) {
                return null;
            }
            if (!(value instanceof Collection<?>)) {
                throw new MongoServerError(34435,
                    "The argument to " + name() + " must be an array, but was of type: " + describeType(value));
            }

            List<?> list = new ArrayList<>((Collection<?>) value);
            Collections.reverse(list);
            return list;
        }
    },

    $second {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateTime(expressionValue, LocalTime::getSecond, document);
        }
    },

    $setDifference {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);

            if (parameters.isAnyNull()) {
                return null;
            }

            Object first = parameters.getFirst();
            Object second = parameters.getSecond();

            if (!(first instanceof Collection)) {
                throw new MongoServerError(17048,
                    "both operands of " + name() + " must be arrays. First argument is of type: " + describeType(first));
            }

            if (!(second instanceof Collection)) {
                throw new MongoServerError(17049,
                    "both operands of " + name() + " must be arrays. First argument is of type: " + describeType(second));
            }

            Set<Object> result = new LinkedHashSet<>((Collection<?>) first);
            result.removeAll((Collection<?>) second);
            return result;
        }

    },

    $setEquals {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            if (expressionValue.size() < 2) {
                throw new MongoServerError(17045, name() + " needs at least two arguments had: " + expressionValue.size());
            }

            final Set<Set<?>> result = new HashSet<>();
            for (Object value : expressionValue) {
                if (!(value instanceof Collection)) {
                    throw new MongoServerError(17044, "All operands of " + name() + " must be arrays. One argument is of type: " + describeType(value));
                }
                result.add(new HashSet<>((Collection<?>) value));
            }
            return result.size() == 1;
        }
    },

    $setIntersection {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Set<?> result = null;
            for (Object value : expressionValue) {
                if (isNullOrMissing(value)) {
                    return null;
                }
                if (!(value instanceof Collection)) {
                    throw new MongoServerError(17047, "All operands of " + name() + " must be arrays. One argument is of type: " + describeType(value));
                }
                if (result == null) {
                    result = new LinkedHashSet<>((Collection<?>) value);
                } else {
                    result.retainAll((Collection<?>) value);
                }
            }
            if (result == null) {
                return Collections.emptySet();
            }
            return result;
        }
    },

    $setIsSubset {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);
            Object first = parameters.getFirst();
            Object second = parameters.getSecond();

            if (!(first instanceof Collection<?>)) {
                throw new MongoServerError(17046, "both operands of " + name() + " must be arrays. First argument is of type: " + describeType(first));
            }

            if (!(second instanceof Collection<?>)) {
                throw new MongoServerError(17042, "both operands of " + name() + " must be arrays. Second argument is of type: " + describeType(second));
            }

            Set<?> one = new HashSet<>((Collection<?>) first);
            Set<?> other = new HashSet<>((Collection<?>) second);
            return other.containsAll(one);
        }
    },

    $setUnion {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Set<Object> result = new LinkedHashSet<>();
            for (Object value : expressionValue) {
                if (isNullOrMissing(value)) {
                    return null;
                }
                if (!(value instanceof Collection<?>)) {
                    throw new MongoServerError(17043,
                        "All operands of " + name() + " must be arrays. One argument is of type: " + describeType(value));
                }
                result.addAll((Collection<?>) value);
            }
            return result;
        }
    },

    $size {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            Object value = requireSingleValue(expressionValue);
            Collection<?> collection = requireArray(17124, value);
            return collection.size();
        }
    },

    $slice {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            if (expressionValue.size() < 2 || expressionValue.size() > 3) {
                throw new MongoServerError(28667, "Expression " + name() + " takes at least 2 arguments, and at most 3, but " + expressionValue.size() + " were passed in.");
            }

            Object first = expressionValue.get(0);
            if (isNullOrMissing(first)) {
                return null;
            }
            if (!(first instanceof List)) {
                throw new MongoServerError(28724, "First argument to " + name() + " must be an array, but is of type: " + describeType(first));
            }
            List<?> list = (List<?>) first;

            Object second = expressionValue.get(1);
            if (!(second instanceof Number)) {
                throw new MongoServerError(28725, "Second argument to " + name() + " must be a numeric value, but is of type: " + describeType(second));
            }

            final List<?> result;
            if (expressionValue.size() > 2) {
                Object third = expressionValue.get(2);
                if (!(third instanceof Number)) {
                    throw new MongoServerError(28725, "Third argument to " + name() + " must be numeric, but is of type: " + describeType(third));
                }

                Number number = (Number) third;
                if (number.intValue() < 0) {
                    throw new MongoServerError(28729, "Third argument to " + name() + " must be positive: " + third);
                }

                int position = ((Number) second).intValue();
                final int offset;
                if (position >= 0) {
                    offset = Math.min(position, list.size());
                } else {
                    offset = Math.max(0, list.size() + position);
                }

                result = list.subList(offset, Math.min(offset + number.intValue(), list.size()));
            } else {
                int n = ((Number) second).intValue();
                if (n >= 0) {
                    result = list.subList(0, Math.min(n, list.size()));
                } else {
                    result = list.subList(Math.max(0, list.size() + n), list.size());
                }
            }

            return result;
        }
    },

    $split {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);
            Object string = parameters.getFirst();
            Object delimiter = parameters.getSecond();

            if (isNullOrMissing(string)) {
                return null;
            }

            if (!(string instanceof String)) {
                throw new MongoServerError(40085,
                    name() + " requires an expression that evaluates to a string as a first argument, found: " + describeType(string));
            }
            if (!(delimiter instanceof String)) {
                throw new MongoServerError(40086,
                    name() + " requires an expression that evaluates to a string as a second argument, found: " + describeType(delimiter));
            }

            return ((String) string).split(Pattern.quote((String) delimiter));
        }
    },

    $subtract {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            TwoParameters parameters = requireTwoParameters(expressionValue);
            Object one = parameters.getFirst();
            Object other = parameters.getSecond();

            if (isNullOrMissing(one) || isNullOrMissing(other)) {
                return null;
            }

            if (!(one instanceof Number && other instanceof Number)) {
                throw new MongoServerError(16556,
                    "cant " + name() + " a " + describeType(one) + " from a " + describeType(other));
            }

            return Utils.subtractNumbers((Number) one, (Number) other);
        }
    },

    $sum {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            if (expressionValue.size() == 1) {
                Object singleValue = expressionValue.get(0);
                if (singleValue instanceof Collection<?>) {
                    return apply(singleValue, document);
                }
            }
            Number sum = 0;
            for (Object value : expressionValue) {
                if (value instanceof Number) {
                    sum = Utils.addNumbers(sum, (Number) value);
                }
            }
            return sum;
        }
    },

    $sqrt {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, Math::sqrt);
        }
    },

    $toLower {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateString(expressionValue, String::toLowerCase);
        }
    },

    $toUpper {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateString(expressionValue, String::toUpperCase);
        }
    },

    $trunc {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateNumericValue(expressionValue, a -> toIntOrLong(a.longValue()));
        }
    },

    $year {
        @Override
        Object apply(List<?> expressionValue, Document document) {
            return evaluateDate(expressionValue, LocalDate::getYear, document);
        }
    },

    ;

    Object apply(Object expressionValue, Document document) {
        List<Object> evaluatedValues = new ArrayList<>();
        if (!(expressionValue instanceof Collection)) {
            evaluatedValues.add(evaluate(expressionValue, document));
        } else {
            for (Object value : ((Collection<?>) expressionValue)) {
                evaluatedValues.add(evaluate(value, document));
            }
        }
        return apply(evaluatedValues, document);
    }

    abstract Object apply(List<?> expressionValue, Document document);

    public static Object evaluateDocument(Object documentWithExpression, Document document) {
        Object evaluatedValue = evaluate(documentWithExpression, document);
        if (evaluatedValue instanceof Document) {
            Document projectedDocument = (Document) evaluatedValue;
            Document result = new Document();
            for (Entry<String, Object> entry : projectedDocument.entrySet()) {
                String field = entry.getKey();
                Object expression = entry.getValue();
                result.put(field, evaluate(expression, document));
            }
            return result;
        } else {
            return evaluatedValue;
        }
    }

    static Object evaluate(Object expression, Document document) {
        if (expression instanceof String && ((String) expression).startsWith("$")) {
            String value = ((String) expression).substring(1);
            if (value.startsWith("$")) {
                if (value.equals("$ROOT")) {
                    return document;
                } else if (value.startsWith("$ROOT.")) {
                    String subKey = value.substring("$ROOT.".length());
                    return Utils.getSubdocumentValue(document, subKey);
                }
                Object subdocumentValue = Utils.getSubdocumentValue(document, value);
                if (!(subdocumentValue instanceof Missing)) {
                    return subdocumentValue;
                }
                String variable = value.substring(1);
                throw new MongoServerError(17276, "Use of undefined variable: " + variable);
            }
            return Utils.getSubdocumentValue(document, value);
        } else if (expression instanceof Document) {
            return evaluateDocumentExpression((Document) expression, document);
        } else {
            return expression;
        }
    }

    private static Object evaluateDocumentExpression(Document expression, Document document) {
        Document result = new Document();
        for (Entry<String, Object> entry : expression.entrySet()) {
            String expressionKey = entry.getKey();
            Object expressionValue = entry.getValue();
            if (expressionKey.startsWith("$")) {
                if (expression.keySet().size() > 1) {
                    throw new MongoServerError(15983, "An object representing an expression must have exactly one field: " + expression);
                }

                final Expression exp;
                try {
                    exp = valueOf(expressionKey);
                } catch (IllegalArgumentException ex) {
                    throw new MongoServerError(168, "InvalidPipelineOperator", "Unrecognized expression '" + expressionKey + "'");
                }
                return exp.apply(expressionValue, document);
            } else {
                result.put(expressionKey, expressionValue);
            }
        }
        return result;
    }

    private static Number toIntOrLong(double value) {
        long number = (long) value;
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            return number;
        } else {
            return Math.toIntExact(number);
        }
    }

}
