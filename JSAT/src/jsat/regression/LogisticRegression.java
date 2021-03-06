
package jsat.regression;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jsat.SingleWeightVectorModel;
import jsat.classifiers.CategoricalResults;
import jsat.classifiers.ClassificationDataSet;
import jsat.classifiers.Classifier;
import jsat.classifiers.DataPoint;
import jsat.classifiers.bayesian.NaiveBayes;
import jsat.exceptions.FailedToFitException;
import jsat.exceptions.UntrainedModelException;
import jsat.linear.DenseVector;
import jsat.linear.SubVector;
import jsat.linear.Vec;
import jsat.math.Function;
import jsat.math.optimization.IterativelyReweightedLeastSquares;
import jsat.math.optimization.Optimizer;
import jsat.utils.FakeExecutor;
import jsat.utils.SystemInfo;

/**
 * Logistic regression is a common method used to fit a probability between binary outputs. 
 * It can also be used to perform regression on a real function. For classification tasks, 
 * when all variables are independent, the results converge to the same prediction values 
 * as {@link NaiveBayes}. When variables have a high degree of correlation, Logistic 
 * Regression should produce better results. 
 * 
 * @author Edward Raff
 */
public class LogisticRegression implements Classifier, Regressor, SingleWeightVectorModel
{

    private static final long serialVersionUID = -5115807516729861730L;
    private Vec coefficents;
    
    /**
     * Logistic regression needs values on the range [0, 1]. The shift makes sure that values are on the range [0, x], for some x
     */
    private double shift;
    /**
     * Logistic regression needs values on the range [0, 1]. The scale makes sure that values of the form [0, x] are converted to [0, 1]
     */
    private double scale;
    
    private static double logit(double z)
    {
        return 1/(1+Math.exp(-z));
    }
    
    private double logitReg(Vec input)
    {
        double z = coefficents.get(0);
        for(int i = 1; i < coefficents.length(); i++)
            z += input.get(i-1)*coefficents.get(i);
        return logit(z);
    }
    
    final private Function logitFun = new Function() {

        public double f(double... x)
        {
            return logitReg(DenseVector.toDenseVec(x));
        }

        public double f(Vec x)
        {
            return logitReg(x);
        }
    };
    
    final private Function logitFunD = new Function() {

        public double f(double... x)
        {
            return logitReg(DenseVector.toDenseVec(x));
        }

        public double f(Vec x)
        {
            double y = logitReg(x);
            return y*(1-y);
        }
    };

    /**
     * Returns the backing vector that containing the learned coefficients for the logistic regression. Changes to it will alter the results of the model
     * @return the backing vector that containing the learned coefficients for the logistic regression.
     */
    public Vec getCoefficents()
    {
        return coefficents;
    }

    @Override
    public double regress(DataPoint data)
    {
        if(coefficents == null)
            throw new UntrainedModelException("Model has not been trained");
        return logitReg(data.getNumericalValues())*scale+shift;
    }

    @Override
    public void train(RegressionDataSet dataSet, boolean parallel)
    {
        List<Vec> inputs = new ArrayList<>(dataSet.getSampleSize());
        for(int i = 0; i < dataSet.getSampleSize(); i++)
            inputs.add(dataSet.getDataPoint(i).getNumericalValues());
        
        coefficents = new DenseVector(dataSet.getNumNumericalVars()+1);
        Vec targetValues = dataSet.getTargetValues();
        double minTarget = targetValues.min();
        double maxTarget = targetValues.max();
        shift = minTarget;
        scale = maxTarget-minTarget;
        
        //Now all values are in the range [0, 1]
        targetValues.subtract(shift);
        targetValues.mutableDivide(scale);
        
        Optimizer optimizer = new IterativelyReweightedLeastSquares();
        
        ExecutorService threadPool = parallel ? Executors.newFixedThreadPool(SystemInfo.LogicalCores) : new FakeExecutor();
        coefficents = optimizer.optimize(1e-5, 100, logitFun, logitFunD, coefficents, inputs, targetValues, threadPool);
        threadPool.shutdownNow();
    }

    @Override
    public boolean supportsWeightedData()
    {
        return false;
    }

    @Override
    public Vec getRawWeight()
    {
        return new SubVector(1, coefficents.length()-1, coefficents);
    }

    @Override
    public double getBias()
    {
        return coefficents.get(0);
    }
    
    @Override
    public Vec getRawWeight(int index)
    {
        if(index < 1)
            return getRawWeight();
        else
            throw new IndexOutOfBoundsException("Model has only 1 weight vector");
    }

    @Override
    public double getBias(int index)
    {
        if (index < 1)
            return getBias();
        else
            throw new IndexOutOfBoundsException("Model has only 1 weight vector");
    }
    
    @Override
    public int numWeightsVecs()
    {
        return 1;
    }

    @Override
    public LogisticRegression clone()
    {
        LogisticRegression clone = new LogisticRegression();
        if(this.coefficents != null)
            clone.coefficents = this.coefficents.clone();
        clone.scale = this.scale;
        clone.shift = this.shift;
        return clone;
    }

    @Override
    public CategoricalResults classify(DataPoint data)
    {
        if(coefficents == null)
            throw new UntrainedModelException("Model has not yet been trained");
        else if(shift != 0 || scale != 1)
            throw new UntrainedModelException("Model was trained for regression, not classifiaction");
        CategoricalResults results = new CategoricalResults(2);
        
        //It looks a little backwards. But if the true class is 0, and we are accurate, then we expect regress to return a value near zero. 
        results.setProb(1, regress(data));
        results.setProb(0, 1.0-results.getProb(1));
        return results;
    }

    @Override
    public void train(ClassificationDataSet dataSet, boolean parallel)
    {
        if(dataSet.getClassSize() != 2)
            throw new FailedToFitException("Logistic Regression works only in the case of two classes, and can not handle " +
                                           dataSet.getClassSize() + " classes");
        RegressionDataSet rds = new RegressionDataSet(dataSet.getNumNumericalVars(), dataSet.getCategories());
        for(int i = 0; i < dataSet.getSampleSize(); i++)
        {
            //getDataPointCategory will return either 0 or 1, so it works perfectly 
            rds.addDataPoint(dataSet.getDataPoint(i), (double)dataSet.getDataPointCategory(i));
        }
        
        
        train(rds, parallel);
        
    }

    @Override
    public void train(ClassificationDataSet dataSet)
    {
        train(dataSet,false);
    }
    
}

